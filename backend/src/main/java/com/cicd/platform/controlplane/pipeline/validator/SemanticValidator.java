package com.cicd.platform.controlplane.pipeline.validator;

import com.cicd.platform.controlplane.pipeline.config.JobConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;

import java.util.HashSet;
import java.util.Set;

public class SemanticValidator {

    public PipelineValidationResult validate(PipelineConfig config) {
        PipelineValidationResult result = new PipelineValidationResult();

        validateStageNamesUnique(config, result);
        validateJobNamesUniquePerStage(config, result);
        validateStageDependenciesExist(config, result);
        validateJobDependenciesExist(config, result);

        return result;
    }

    private void validateStageNamesUnique(PipelineConfig config, PipelineValidationResult result) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < config.getStages().size(); i++) {
            StageConfig stage = config.getStages().get(i);
            if (stage.getName() != null && !stage.getName().isBlank()) {
                if (!seen.add(stage.getName().toLowerCase())) {
                    result.addError("pipeline.stages[" + i + "].name", "DUPLICATE",
                            "Duplicate stage name: '" + stage.getName() + "'");
                }
            }
        }
    }

    private void validateJobNamesUniquePerStage(PipelineConfig config, PipelineValidationResult result) {
        for (int i = 0; i < config.getStages().size(); i++) {
            StageConfig stage = config.getStages().get(i);
            if (stage.getJobs() == null) continue;

            Set<String> seen = new HashSet<>();
            for (int j = 0; j < stage.getJobs().size(); j++) {
                JobConfig job = stage.getJobs().get(j);
                if (job.getName() != null && !job.getName().isBlank()) {
                    if (!seen.add(job.getName().toLowerCase())) {
                        result.addError("pipeline.stages[" + i + "].jobs[" + j + "].name", "DUPLICATE",
                                "Duplicate job name '" + job.getName() + "' in stage '" + stage.getName() + "'");
                    }
                }
            }
        }
    }

    private void validateStageDependenciesExist(PipelineConfig config, PipelineValidationResult result) {
        Set<String> stageNames = new HashSet<>();
        for (StageConfig stage : config.getStages()) {
            if (stage.getName() != null) {
                stageNames.add(stage.getName().toLowerCase());
            }
        }

        for (int i = 0; i < config.getStages().size(); i++) {
            StageConfig stage = config.getStages().get(i);
            if (stage.getDependsOn() == null) continue;

            for (String dep : stage.getDependsOn()) {
                if (dep != null && !dep.isBlank() && !stageNames.contains(dep.toLowerCase())) {
                    result.addError("pipeline.stages[" + i + "].dependsOn", "INVALID_REFERENCE",
                            "Stage '" + stage.getName() + "' depends on unknown stage '" + dep + "'");
                }
            }
        }
    }

    private void validateJobDependenciesExist(PipelineConfig config, PipelineValidationResult result) {
        for (int i = 0; i < config.getStages().size(); i++) {
            StageConfig stage = config.getStages().get(i);
            if (stage.getJobs() == null) continue;

            Set<String> jobNames = new HashSet<>();
            for (JobConfig job : stage.getJobs()) {
                if (job.getName() != null) {
                    jobNames.add(job.getName().toLowerCase());
                }
            }

            for (int j = 0; j < stage.getJobs().size(); j++) {
                JobConfig job = stage.getJobs().get(j);
                if (job.getDependsOn() == null) continue;

                for (String dep : job.getDependsOn()) {
                    if (dep != null && !dep.isBlank() && !jobNames.contains(dep.toLowerCase())) {
                        result.addError("pipeline.stages[" + i + "].jobs[" + j + "].dependsOn", "INVALID_REFERENCE",
                                "Job '" + job.getName() + "' depends on unknown job '" + dep + "'");
                    }
                }
            }
        }
    }
}
