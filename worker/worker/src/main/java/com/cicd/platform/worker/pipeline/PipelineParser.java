package com.cicd.platform.worker.pipeline;

import com.cicd.platform.worker.exception.PipelineConfigurationException;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.PipelineDefinition;
import com.cicd.platform.worker.pipeline.model.StageDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the Phase 3 pipeline YAML (Pipeline → Stage → Job → Step) into the
 * immutable internal model.
 *
 * <p>Only two step types are recognised: {@code run} (shell command) and
 * {@code buildImage} (docker image build). Unknown or malformed steps are
 * rejected at parse time so a malicious or broken pipeline never reaches the
 * execution engine.</p>
 */
@Component
public class PipelineParser {

    private static final Logger log = LoggerFactory.getLogger(PipelineParser.class);

    private final ObjectMapper yamlMapper;

    public PipelineParser(@Qualifier("yamlObjectMapper") ObjectMapper yamlMapper) {
        this.yamlMapper = yamlMapper;
    }

    public PipelineDefinition parse(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new PipelineConfigurationException("Cannot read pipeline file " + file + ": " + e.getMessage(), e);
        }
        return parseContent(content, file.toString());
    }

    public PipelineDefinition parseContent(String yaml, String source) {
        JsonNode root;
        try {
            root = yamlMapper.readTree(yaml);
        } catch (Exception e) {
            throw new PipelineConfigurationException("Invalid pipeline YAML in " + source + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new PipelineConfigurationException("Pipeline YAML in " + source + " must be a mapping");
        }
        JsonNode pipeline = root.path("pipeline");
        if (pipeline.isMissingNode() || !pipeline.isObject()) {
            throw new PipelineConfigurationException("Missing required top-level 'pipeline' section in " + source);
        }

        String name = pipeline.path("name").asText("");
        if (name.isBlank()) {
            throw new PipelineConfigurationException("Pipeline 'name' is required in " + source);
        }

        JsonNode stagesNode = pipeline.path("stages");
        if (!stagesNode.isArray() || stagesNode.isEmpty()) {
            throw new PipelineConfigurationException("Pipeline must define at least one stage in " + source);
        }

        List<StageDefinition> stages = new ArrayList<>();
        for (JsonNode stageNode : stagesNode) {
            stages.add(parseStage(stageNode, source));
        }
        log.info("Parsed pipeline '{}' with {} stage(s) from {}", name, stages.size(), source);
        return new PipelineDefinition(name, stages, source);
    }

    private StageDefinition parseStage(JsonNode stageNode, String source) {
        if (!stageNode.isObject()) {
            throw new PipelineConfigurationException("Each stage must be a mapping in " + source);
        }
        String name = stageNode.path("name").asText("");
        if (name.isBlank()) {
            throw new PipelineConfigurationException("Stage 'name' is required in " + source);
        }
        JsonNode jobsNode = stageNode.path("jobs");
        if (!jobsNode.isArray() || jobsNode.isEmpty()) {
            throw new PipelineConfigurationException("Stage '" + name + "' must define at least one job in " + source);
        }
        List<JobDefinition> jobs = new ArrayList<>();
        for (JsonNode jobNode : jobsNode) {
            jobs.add(parseJob(jobNode, name, source));
        }
        return new StageDefinition(name, jobs);
    }

    private JobDefinition parseJob(JsonNode jobNode, String stageName, String source) {
        if (!jobNode.isObject()) {
            throw new PipelineConfigurationException("Each job in stage '" + stageName + "' must be a mapping in " + source);
        }
        String name = jobNode.path("name").asText("");
        if (name.isBlank()) {
            throw new PipelineConfigurationException("Job 'name' is required in stage '" + stageName + "' in " + source);
        }
        String workingDirectory = emptyToNull(jobNode.path("workingDirectory").asText(""));
        String image = emptyToNull(jobNode.path("image").asText(""));
        Map<String, String> env = parseEnv(jobNode.path("env"), "job '" + name + "'", source);
        List<String> artifacts = parseArtifacts(jobNode.path("artifacts"), "job '" + name + "'", source);

        JsonNode stepsNode = jobNode.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new PipelineConfigurationException(
                    "Job '" + name + "' in stage '" + stageName + "' must define at least one step in " + source);
        }
        List<StepDefinition> steps = new ArrayList<>();
        int index = 0;
        for (JsonNode stepNode : stepsNode) {
            steps.add(parseStep(stepNode, name, index++, source));
        }
        return new JobDefinition(name, workingDirectory, image, env, steps, artifacts);
    }

    private StepDefinition parseStep(JsonNode stepNode, String jobName, int index, String source) {
        if (!stepNode.isObject()) {
            throw new PipelineConfigurationException(
                    "Step " + index + " of job '" + jobName + "' must be a mapping in " + source);
        }
        if (stepNode.size() == 0) {
            throw new PipelineConfigurationException(
                    "Step " + index + " of job '" + jobName + "' is empty in " + source);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = stepNode.fields();
        Map.Entry<String, JsonNode> first = fields.next();
        String key = first.getKey();
        JsonNode value = first.getValue();

        switch (key) {
            case "run" -> {
                String command = value.asText("");
                if (command.isBlank()) {
                    throw new PipelineConfigurationException(
                            "Step " + index + " of job '" + jobName + "' has an empty 'run' command in " + source);
                }
                return new StepDefinition(StepType.RUN, deriveStepName(command, index), command,
                        emptyToNull(stepNode.path("image").asText("")));
            }
            case "buildImage" -> {
                String image = value.asText("");
                if (image.isBlank()) {
                    throw new PipelineConfigurationException(
                            "Step " + index + " of job '" + jobName + "' has an empty 'buildImage' in " + source);
                }
                return new StepDefinition(StepType.BUILD_IMAGE, "build-image-" + image, image, null);
            }
            default -> throw new PipelineConfigurationException(
                    "Unknown step type '" + key + "' in job '" + jobName + "' (supported: run, buildImage) in " + source);
        }
    }

    private Map<String, String> parseEnv(JsonNode envNode, String context, String source) {
        if (envNode.isMissingNode()) {
            return Map.of();
        }
        if (!envNode.isObject()) {
            throw new PipelineConfigurationException("'env' of " + context + " must be a mapping in " + source);
        }
        Map<String, String> env = new LinkedHashMap<>();
        envNode.fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText("")));
        return env;
    }

    private List<String> parseArtifacts(JsonNode artifactsNode, String context, String source) {
        if (artifactsNode.isMissingNode()) {
            return List.of();
        }
        if (!artifactsNode.isArray()) {
            throw new PipelineConfigurationException("'artifacts' of " + context + " must be a list in " + source);
        }
        List<String> artifacts = new ArrayList<>();
        artifactsNode.forEach(n -> artifacts.add(n.asText("")));
        return artifacts;
    }

    private String deriveStepName(String command, int index) {
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return "step-" + (index + 1);
        }
        String firstToken = trimmed.split("\\s+")[0];
        if (firstToken.length() > 40) {
            firstToken = firstToken.substring(0, 40);
        }
        return firstToken;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
