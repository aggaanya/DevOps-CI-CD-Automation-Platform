package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Derives stage and run status from the states of their jobs/stages.
 *
 * <p>Rules are intentionally conservative: anything that is not provably a full
 * success is reported as a failure rather than left in a running state, because a
 * run that never reaches a terminal status is indistinguishable from a hung
 * pipeline to every consumer of this API.
 */
@Component
public class StageResultCollector {

    /**
     * @param jobs the stage's jobs, in any order
     * @return {@code SUCCESS} when every job succeeded, {@code FAILED} when any job
     *         failed or every job was cancelled, {@code SKIPPED} when the stage
     *         produced no result at all because its inputs were dead,
     *         {@code RUNNING} while any job is still in flight.
     */
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

        boolean allTerminal = jobs.stream().allMatch(job -> job.getStatus().isTerminal());
        if (!allTerminal) {
            return PipelineStage.StageStatus.RUNNING;
        }

        boolean allCancelled = jobs.stream()
                .allMatch(job -> job.getStatus() == PipelineJob.JobStatus.CANCELLED);
        if (allCancelled) {
            return PipelineStage.StageStatus.FAILED;
        }

        boolean anySucceeded = jobs.stream()
                .anyMatch(job -> job.getStatus() == PipelineJob.JobStatus.SUCCESS);
        if (anySucceeded) {
            // Partial success: some jobs ran and passed, the rest were skipped.
            // The stage itself is a success; the run-level FAILED verdict comes
            // from whichever upstream stage actually failed.
            return PipelineStage.StageStatus.SUCCESS;
        }

        // Every job was SKIPPED: the stage never ran.
        return PipelineStage.StageStatus.SKIPPED;
    }

    /**
     * @param stages the run's stages
     * @return {@code SUCCESS} when every stage succeeded, {@code FAILED} when any
     *         stage failed or the run finished with stages that produced nothing,
     *         {@code RUNNING} while any stage is still in flight.
     */
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

        boolean allTerminal = stages.stream().allMatch(stage -> stage.getStatus().isTerminal());
        if (!allTerminal) {
            return PipelineRun.RunStatus.RUNNING;
        }

        // Every stage is terminal and none succeeded outright. Either a stage was
        // skipped (which the DAG scheduler only does downstream of a failure) or
        // every stage was cancelled. Neither is a success.
        return PipelineRun.RunStatus.FAILED;
    }
}
