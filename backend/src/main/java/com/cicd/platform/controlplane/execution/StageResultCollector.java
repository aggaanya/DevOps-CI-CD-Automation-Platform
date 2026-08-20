package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StageResultCollector {

    public PipelineStage.StageStatus evaluateStageStatus(PipelineStage stage, List<PipelineJob> jobs) {
        if (jobs.isEmpty()) {
            return PipelineStage.StageStatus.SUCCESS;
        }

        boolean allSuccess = jobs.stream()
                .allMatch(job -> job.getStatus() == PipelineJob.JobStatus.SUCCESS);
        if (allSuccess) {
            return PipelineStage.StageStatus.SUCCESS;
        }

        boolean anyFailed = jobs.stream()
                .anyMatch(job -> job.getStatus() == PipelineJob.JobStatus.FAILED);
        if (anyFailed) {
            return PipelineStage.StageStatus.FAILED;
        }

        boolean allCancelled = jobs.stream()
                .allMatch(job -> job.getStatus() == PipelineJob.JobStatus.CANCELLED);
        if (allCancelled) {
            return PipelineStage.StageStatus.FAILED;
        }

        return PipelineStage.StageStatus.RUNNING;
    }

    public PipelineRun.RunStatus evaluateRunStatus(List<PipelineStage> stages) {
        if (stages.isEmpty()) {
            return PipelineRun.RunStatus.SUCCESS;
        }

        boolean allSuccess = stages.stream()
                .allMatch(stage -> stage.getStatus() == PipelineStage.StageStatus.SUCCESS);
        if (allSuccess) {
            return PipelineRun.RunStatus.SUCCESS;
        }

        boolean anyFailed = stages.stream()
                .anyMatch(stage -> stage.getStatus() == PipelineStage.StageStatus.FAILED);
        if (anyFailed) {
            return PipelineRun.RunStatus.FAILED;
        }

        return PipelineRun.RunStatus.RUNNING;
    }
}
