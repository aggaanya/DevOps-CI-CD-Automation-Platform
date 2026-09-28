package com.cicd.platform.controlplane.pipeline;

import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.pipeline.config.JobConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;
import com.cicd.platform.controlplane.pipeline.config.StepConfig;

import java.util.ArrayList;
import java.util.List;

public class PipelineConfigMapper {

    public record StageDefinition(String name, int orderIndex, List<String> dependsOn, List<JobDefinition> jobs) {}
    public record JobDefinition(String name, PipelineJob.JobType jobType, List<String> dependsOn,
                                List<StepDefinition> steps) {
        public JobDefinition(String name, PipelineJob.JobType jobType, List<String> dependsOn) {
            this(name, jobType, dependsOn, List.of());
        }
    }

    /** One executable command inside a job. */
    public record StepDefinition(String name, String run) {}

    public Pipeline toPipeline(PipelineConfig config, com.cicd.platform.controlplane.domain.entity.Project project) {
        return new Pipeline(project, config.getName(), config.getDescription());
    }

    public List<StageDefinition> toStageDefinitions(PipelineConfig config) {
        List<StageDefinition> definitions = new ArrayList<>();
        if (config.getStages() == null) return definitions;

        for (int i = 0; i < config.getStages().size(); i++) {
            StageConfig stage = config.getStages().get(i);
            List<JobDefinition> jobDefs = new ArrayList<>();
            if (stage.getJobs() != null) {
                for (JobConfig job : stage.getJobs()) {
                    jobDefs.add(new JobDefinition(job.getName(), resolveJobType(job.getType()),
                            job.getDependsOn() != null ? job.getDependsOn() : List.of(),
                            toStepDefinitions(job.getSteps())));
                }
            }
            definitions.add(new StageDefinition(stage.getName(), i,
                    stage.getDependsOn() != null ? stage.getDependsOn() : List.of(), jobDefs));
        }
        return definitions;
    }

    private List<StepDefinition> toStepDefinitions(List<StepConfig> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<StepDefinition> out = new ArrayList<>(steps.size());
        int index = 0;
        for (StepConfig step : steps) {
            if (step == null || step.getRun() == null || step.getRun().isBlank()) {
                continue;
            }
            String name = step.getName() == null || step.getName().isBlank()
                    ? "step-" + (index + 1)
                    : step.getName().trim();
            out.add(new StepDefinition(name, step.getRun().trim()));
            index++;
        }
        return out;
    }

    public PipelineJob.JobType resolveJobType(String type) {
        if (type == null || type.isBlank()) {
            return PipelineJob.JobType.CUSTOM;
        }
        try {
            return PipelineJob.JobType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PipelineJob.JobType.CUSTOM;
        }
    }
}
