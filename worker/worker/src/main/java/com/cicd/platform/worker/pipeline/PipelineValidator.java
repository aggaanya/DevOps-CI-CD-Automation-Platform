package com.cicd.platform.worker.pipeline;

import com.cicd.platform.worker.exception.PipelineConfigurationException;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.PipelineDefinition;
import com.cicd.platform.worker.pipeline.model.StageDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural validation of the parsed pipeline definition. Runs before any
 * command is executed so that malformed configuration fails fast and the
 * job is reported as FAILED without touching the sandbox.
 */
@Component
public class PipelineValidator {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern WORKDIR_PATTERN = Pattern.compile("^(?!/)[A-Za-z0-9._\\-/]{0,255}$");
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    public void validate(PipelineDefinition pipeline) {
        validateName("pipeline", pipeline.name());
        if (pipeline.stages().isEmpty()) {
            throw new PipelineConfigurationException("Pipeline '" + pipeline.name() + "' has no stages");
        }
        Set<String> stageNames = new HashSet<>();
        for (StageDefinition stage : pipeline.stages()) {
            if (!stageNames.add(stage.name())) {
                throw new PipelineConfigurationException(
                        "Duplicate stage name '" + stage.name() + "' in pipeline '" + pipeline.name() + "'");
            }
            validateStage(stage, pipeline.name());
        }
    }

    private void validateStage(StageDefinition stage, String pipelineName) {
        validateName("stage", stage.name());
        if (stage.jobs().isEmpty()) {
            throw new PipelineConfigurationException(
                    "Stage '" + stage.name() + "' in pipeline '" + pipelineName + "' has no jobs");
        }
        Set<String> jobNames = new HashSet<>();
        for (JobDefinition job : stage.jobs()) {
            if (!jobNames.add(job.name())) {
                throw new PipelineConfigurationException(
                        "Duplicate job name '" + job.name() + "' in stage '" + stage.name() + "'");
            }
            validateJob(job, stage.name(), pipelineName);
        }
    }

    private void validateJob(JobDefinition job, String stageName, String pipelineName) {
        validateName("job", job.name());
        if (job.workingDirectory() != null) {
            if (!WORKDIR_PATTERN.matcher(job.workingDirectory()).matches() || containsParentSegment(job.workingDirectory())) {
                throw new PipelineConfigurationException(
                        "Invalid workingDirectory '" + job.workingDirectory() + "' in job '" + job.name() + "'");
            }
        }
        if (job.image() != null && job.image().length() > 255) {
            throw new PipelineConfigurationException("Job image name too long in job '" + job.name() + "'");
        }
        for (Map.Entry<String, String> entry : job.env().entrySet()) {
            if (!ENV_KEY_PATTERN.matcher(entry.getKey()).matches()) {
                throw new PipelineConfigurationException(
                        "Invalid environment variable name '" + entry.getKey() + "' in job '" + job.name() + "'");
            }
            if (containsSecretName(entry.getKey())) {
                throw new PipelineConfigurationException(
                        "Environment variable '" + entry.getKey() + "' in job '" + job.name()
                                + "' is blocked by security policy");
            }
            if (entry.getValue() == null || containsControlChars(entry.getValue())) {
                throw new PipelineConfigurationException(
                        "Invalid environment variable value for '" + entry.getKey() + "' in job '" + job.name() + "'");
            }
        }
        if (job.steps().isEmpty()) {
            throw new PipelineConfigurationException(
                    "Job '" + job.name() + "' in stage '" + stageName + "' has no steps");
        }
        for (StepDefinition step : job.steps()) {
            if (step.type() == StepType.RUN) {
                if (step.command() == null || step.command().isBlank()) {
                    throw new PipelineConfigurationException(
                            "Step with empty command in job '" + job.name() + "'");
                }
                if (step.command().length() > 4096) {
                    throw new PipelineConfigurationException(
                            "Command exceeds 4096 characters in job '" + job.name() + "'");
                }
                if (containsControlChars(step.command())) {
                    throw new PipelineConfigurationException(
                            "Command contains control characters in job '" + job.name() + "'");
                }
            }
        }
    }

    private void validateName(String kind, String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new PipelineConfigurationException(
                    "Invalid " + kind + " name '" + name + "'. Allowed: 1-64 chars, letters, digits, '.', '_', '-'");
        }
    }

    private boolean containsParentSegment(String value) {
        for (String segment : value.split("/")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSecretName(String key) {
        String upper = key.toUpperCase();
        String[] blocked = {"TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "PRIVATE_KEY",
                "AWS_ACCESS_KEY", "AWS_SECRET", "API_KEY", "CLIENT_SECRET", "GITHUB_TOKEN", "AUTHORIZATION"};
        for (String fragment : blocked) {
            if (upper.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
