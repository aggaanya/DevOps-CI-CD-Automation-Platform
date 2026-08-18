package com.cicd.platform.controlplane.pipeline.validator;

import com.cicd.platform.controlplane.pipeline.config.JobConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;

import java.util.Set;

public class SchemaValidator {

    private static final Set<String> VALID_JOB_TYPES = Set.of(
            "BUILD", "TEST", "SCAN", "DEPLOY", "PACKAGE", "CUSTOM"
    );

    public PipelineValidationResult validate(PipelineConfig config) {
        PipelineValidationResult result = new PipelineValidationResult();

        if (config.getName() == null || config.getName().isBlank()) {
            result.addError("pipeline.name", "REQUIRED", "Pipeline name is required");
        } else if (config.getName().length() > 255) {
            result.addError("pipeline.name", "SIZE", "Pipeline name must not exceed 255 characters");
        }

        if (config.getStages() == null || config.getStages().isEmpty()) {
            result.addError("pipeline.stages", "REQUIRED", "Pipeline must contain at least one stage");
        } else {
            validateStages(config, result);
        }

        return result;
    }

    private void validateStages(PipelineConfig config, PipelineValidationResult result) {
        for (int i = 0; i < config.getStages().size(); i++) {
            StageConfig stage = config.getStages().get(i);
            String stagePath = "pipeline.stages[" + i + "]";

            if (stage == null) {
                result.addError(stagePath, "REQUIRED", "Stage definition must not be null");
                continue;
            }

            if (stage.getName() == null || stage.getName().isBlank()) {
                result.addError(stagePath + ".name", "REQUIRED", "Stage name is required");
            } else if (stage.getName().length() > 255) {
                result.addError(stagePath + ".name", "SIZE", "Stage name must not exceed 255 characters");
            }

            if (stage.getJobs() == null || stage.getJobs().isEmpty()) {
                result.addError(stagePath + ".jobs", "REQUIRED", "Stage must contain at least one job");
            } else {
                validateJobs(stage, stagePath, result);
            }
        }
    }

    private void validateJobs(StageConfig stage, String stagePath, PipelineValidationResult result) {
        for (int j = 0; j < stage.getJobs().size(); j++) {
            JobConfig job = stage.getJobs().get(j);
            String jobPath = stagePath + ".jobs[" + j + "]";

            if (job == null) {
                result.addError(jobPath, "REQUIRED", "Job definition must not be null");
                continue;
            }

            if (job.getName() == null || job.getName().isBlank()) {
                result.addError(jobPath + ".name", "REQUIRED", "Job name is required");
            } else if (job.getName().length() > 255) {
                result.addError(jobPath + ".name", "SIZE", "Job name must not exceed 255 characters");
            }

            if (job.getType() == null || job.getType().isBlank()) {
                result.addError(jobPath + ".type", "REQUIRED", "Job type is required");
            } else if (!VALID_JOB_TYPES.contains(job.getType().toUpperCase())) {
                result.addError(jobPath + ".type", "INVALID",
                        "Invalid job type '" + job.getType() + "'. Valid types: " + VALID_JOB_TYPES);
            }
        }
    }
}
