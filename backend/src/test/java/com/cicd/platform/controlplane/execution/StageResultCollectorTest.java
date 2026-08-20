package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class StageResultCollectorTest {

    @InjectMocks private StageResultCollector collector;

    @Test
    void evaluateStageStatus_allJobsSuccess_returnsSuccess() {
        PipelineStage stage = new PipelineStage();
        PipelineJob job1 = new PipelineJob();
        job1.setStatus(PipelineJob.JobStatus.SUCCESS);
        PipelineJob job2 = new PipelineJob();
        job2.setStatus(PipelineJob.JobStatus.SUCCESS);

        PipelineStage.StageStatus status = collector.evaluateStageStatus(stage, List.of(job1, job2));

        assertEquals(PipelineStage.StageStatus.SUCCESS, status);
    }

    @Test
    void evaluateStageStatus_oneJobFailed_returnsFailed() {
        PipelineStage stage = new PipelineStage();
        PipelineJob job1 = new PipelineJob();
        job1.setStatus(PipelineJob.JobStatus.SUCCESS);
        PipelineJob job2 = new PipelineJob();
        job2.setStatus(PipelineJob.JobStatus.FAILED);

        PipelineStage.StageStatus status = collector.evaluateStageStatus(stage, List.of(job1, job2));

        assertEquals(PipelineStage.StageStatus.FAILED, status);
    }

    @Test
    void evaluateRunStatus_allStagesSuccess_returnsSuccess() {
        PipelineStage s1 = new PipelineStage();
        s1.setStatus(PipelineStage.StageStatus.SUCCESS);
        PipelineStage s2 = new PipelineStage();
        s2.setStatus(PipelineStage.StageStatus.SUCCESS);

        PipelineRun.RunStatus status = collector.evaluateRunStatus(List.of(s1, s2));

        assertEquals(PipelineRun.RunStatus.SUCCESS, status);
    }

    @Test
    void evaluateRunStatus_oneStageFailed_returnsFailed() {
        PipelineStage s1 = new PipelineStage();
        s1.setStatus(PipelineStage.StageStatus.SUCCESS);
        PipelineStage s2 = new PipelineStage();
        s2.setStatus(PipelineStage.StageStatus.FAILED);

        PipelineRun.RunStatus status = collector.evaluateRunStatus(List.of(s1, s2));

        assertEquals(PipelineRun.RunStatus.FAILED, status);
    }

    @Test
    void evaluateStageStatus_allJobsCancelled_returnsFailed() {
        PipelineStage stage = new PipelineStage();
        PipelineJob job1 = new PipelineJob();
        job1.setStatus(PipelineJob.JobStatus.CANCELLED);
        PipelineJob job2 = new PipelineJob();
        job2.setStatus(PipelineJob.JobStatus.CANCELLED);

        PipelineStage.StageStatus status = collector.evaluateStageStatus(stage, List.of(job1, job2));

        assertEquals(PipelineStage.StageStatus.FAILED, status);
    }

    @Test
    void evaluateStageStatus_emptyJobs_returnsSuccess() {
        PipelineStage stage = new PipelineStage();

        PipelineStage.StageStatus status = collector.evaluateStageStatus(stage, List.of());

        assertEquals(PipelineStage.StageStatus.SUCCESS, status);
    }

    @Test
    void evaluateRunStatus_emptyStages_returnsSuccess() {
        PipelineRun.RunStatus status = collector.evaluateRunStatus(List.of());

        assertEquals(PipelineRun.RunStatus.SUCCESS, status);
    }

    @Test
    void evaluateRunStatus_mixedSuccessAndSkipped_returnsRunning() {
        PipelineStage s1 = new PipelineStage();
        s1.setStatus(PipelineStage.StageStatus.SUCCESS);
        PipelineStage s2 = new PipelineStage();
        s2.setStatus(PipelineStage.StageStatus.SKIPPED);

        PipelineRun.RunStatus status = collector.evaluateRunStatus(List.of(s1, s2));

        assertEquals(PipelineRun.RunStatus.RUNNING, status);
    }

    @Test
    void evaluateRunStatus_oneStillRunning_returnsRunning() {
        PipelineStage s1 = new PipelineStage();
        s1.setStatus(PipelineStage.StageStatus.SUCCESS);
        PipelineStage s2 = new PipelineStage();
        s2.setStatus(PipelineStage.StageStatus.RUNNING);

        PipelineRun.RunStatus status = collector.evaluateRunStatus(List.of(s1, s2));

        assertEquals(PipelineRun.RunStatus.RUNNING, status);
    }

    @Test
    void evaluateStageStatus_mixSuccessAndFailed_returnsFailed() {
        PipelineStage stage = new PipelineStage();
        PipelineJob job1 = new PipelineJob();
        job1.setStatus(PipelineJob.JobStatus.SUCCESS);
        PipelineJob job2 = new PipelineJob();
        job2.setStatus(PipelineJob.JobStatus.FAILED);

        PipelineStage.StageStatus status = collector.evaluateStageStatus(stage, List.of(job1, job2));

        assertEquals(PipelineStage.StageStatus.FAILED, status);
    }

    @Test
    void evaluateStageStatus_allJobsStillPending_returnsRunning() {
        PipelineStage stage = new PipelineStage();
        PipelineJob job1 = new PipelineJob();
        job1.setStatus(PipelineJob.JobStatus.PENDING);
        PipelineJob job2 = new PipelineJob();
        job2.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineStage.StageStatus status = collector.evaluateStageStatus(stage, List.of(job1, job2));

        assertEquals(PipelineStage.StageStatus.RUNNING, status);
    }
}
