package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunStateSettlerTest {

    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private PipelineJobRepository pipelineJobRepository;
    @Mock private StageResultCollector stageResultCollector;
    @Mock private OutboxEventService outboxEventService;

    @InjectMocks private RunStateSettler settler;

    private static UUID setId(Object entity) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        UUID id = UUID.randomUUID();
        field.set(entity, id);
        return id;
    }

    private static PipelineJob job(PipelineStage stage, PipelineJob.JobStatus status) throws Exception {
        PipelineJob job = new PipelineJob(stage, "j", PipelineJob.JobType.CUSTOM);
        setId(job);
        job.setStatus(status);
        return job;
    }

    @Test
    void settleRun_nullRunId_returnsNull() {
        assertNull(settler.settleRun(null));
        verify(pipelineStageRepository, never()).findByPipelineRunIdOrderByOrderIndexAsc(any());
    }

    @Test
    void settleRun_noStages_returnsNull() {
        UUID runId = UUID.randomUUID();
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId)).thenReturn(List.of());

        assertNull(settler.settleRun(runId));
        verify(pipelineRunRepository, never()).completeRun(any(), any(), any(), any());
    }

    @Test
    void settleRun_notAllJobsTerminal_settlesNothing() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage(null, "build", 0);
        UUID stageId = setId(stage);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);
        List<PipelineJob> jobs = List.of(
                job(stage, PipelineJob.JobStatus.SUCCESS),
                job(stage, PipelineJob.JobStatus.RUNNING));

        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId)).thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(jobs);

        assertNull(settler.settleRun(runId));
        verify(pipelineStageRepository, never()).completeStage(any(), any(), any(), any(), any());
        verify(outboxEventService, never()).publishEvent(eq("RUN_COMPLETED"), any(), any(), any());
    }

    @Test
    void settleRun_allJobsTerminal_settlesStageThenRun() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage(null, "build", 0);
        UUID stageId = setId(stage);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);
        List<PipelineJob> jobs = List.of(
                job(stage, PipelineJob.JobStatus.SUCCESS),
                job(stage, PipelineJob.JobStatus.SUCCESS));

        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId)).thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(jobs);
        when(stageResultCollector.evaluateStageStatus(eq(stage), eq(jobs)))
                .thenReturn(PipelineStage.StageStatus.SUCCESS);
        when(pipelineStageRepository.completeStage(eq(stageId),
                eq(List.of(PipelineStage.StageStatus.PENDING, PipelineStage.StageStatus.RUNNING)),
                eq(PipelineStage.StageStatus.SUCCESS), any(), any())).thenReturn(1);
        when(stageResultCollector.evaluateRunStatus(List.of(stage)))
                .thenReturn(PipelineRun.RunStatus.SUCCESS);
        when(pipelineRunRepository.completeRun(eq(runId), eq(PipelineRun.RunStatus.RUNNING),
                eq(PipelineRun.RunStatus.SUCCESS), any())).thenReturn(1);

        PipelineRun.RunStatus result = settler.settleRun(runId);

        assertEquals(PipelineRun.RunStatus.SUCCESS, result);
        assertEquals(PipelineStage.StageStatus.SUCCESS, stage.getStatus());
        verify(outboxEventService).publishEvent(eq("STAGE_COMPLETED"), eq("PipelineStage"),
                eq(stageId), any());
        verify(outboxEventService).publishEvent(eq("RUN_COMPLETED"), eq("PipelineRun"),
                eq(runId), any());
    }

    @Test
    void settleRun_runAlreadyTerminal_doesNotReEmitEvents() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage(null, "build", 0);
        setId(stage);
        stage.setStatus(PipelineStage.StageStatus.SUCCESS);
        List<PipelineJob> jobs = List.of(job(stage, PipelineJob.JobStatus.CANCELLED));

        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId)).thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stage.getId())).thenReturn(jobs);
        when(stageResultCollector.evaluateRunStatus(List.of(stage)))
                .thenReturn(PipelineRun.RunStatus.FAILED);
        when(pipelineRunRepository.completeRun(eq(runId), eq(PipelineRun.RunStatus.RUNNING),
                eq(PipelineRun.RunStatus.FAILED), any())).thenReturn(0);

        assertNull(settler.settleRun(runId));
        verify(pipelineStageRepository, never()).completeStage(any(), any(), any(), any(), any());
        verify(outboxEventService, never()).publishEvent(eq("STAGE_COMPLETED"), any(), any(), any());
        verify(outboxEventService, never()).publishEvent(eq("RUN_COMPLETED"), any(), any(), any());
    }

    @Test
    void settleRun_mixedStages_settlesRunAsFailed() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineStage build = new PipelineStage(null, "build", 0);
        UUID buildId = setId(build);
        build.setStatus(PipelineStage.StageStatus.RUNNING);
        List<PipelineJob> buildJobs = List.of(job(build, PipelineJob.JobStatus.FAILED));

        PipelineStage test = new PipelineStage(null, "test", 1);
        setId(test);
        test.setStatus(PipelineStage.StageStatus.RUNNING);
        List<PipelineJob> testJobs = List.of(job(test, PipelineJob.JobStatus.SUCCESS));

        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(build, test));
        when(pipelineJobRepository.findByPipelineStageId(buildId)).thenReturn(buildJobs);
        when(pipelineJobRepository.findByPipelineStageId(test.getId())).thenReturn(testJobs);
        when(stageResultCollector.evaluateStageStatus(build, buildJobs))
                .thenReturn(PipelineStage.StageStatus.FAILED);
        when(stageResultCollector.evaluateStageStatus(test, testJobs))
                .thenReturn(PipelineStage.StageStatus.SUCCESS);
        when(pipelineStageRepository.completeStage(any(), any(), any(), any(), any())).thenReturn(1);
        when(stageResultCollector.evaluateRunStatus(List.of(build, test)))
                .thenReturn(PipelineRun.RunStatus.FAILED);
        when(pipelineRunRepository.completeRun(eq(runId), eq(PipelineRun.RunStatus.RUNNING),
                eq(PipelineRun.RunStatus.FAILED), any())).thenReturn(1);

        PipelineRun.RunStatus result = settler.settleRun(runId);

        assertEquals(PipelineRun.RunStatus.FAILED, result);
        verify(outboxEventService).publishEvent(eq("STAGE_COMPLETED"), eq("PipelineStage"),
                eq(buildId), any());
        verify(outboxEventService).publishEvent(eq("RUN_COMPLETED"), eq("PipelineRun"),
                eq(runId), any());
    }
}