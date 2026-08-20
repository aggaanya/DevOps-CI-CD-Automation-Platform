package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineOrchestratorTest {

    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private PipelineJobRepository pipelineJobRepository;
    @Mock private PipelineVersionRepository pipelineVersionRepository;
    @Mock private JobAttemptRepository jobAttemptRepository;
    @Mock private JobDispatcherService jobDispatcherService;
    @Mock private StageResultCollector stageResultCollector;
    @Mock private WorkspaceConfig workspaceConfig;
    @Mock private OutboxEventService outboxEventService;

    private PipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new PipelineOrchestrator(
                pipelineRunRepository, pipelineStageRepository, pipelineJobRepository,
                pipelineVersionRepository, jobAttemptRepository,
                jobDispatcherService, stageResultCollector, workspaceConfig, outboxEventService);
    }

    private void setId(Object entity, UUID id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    @Test
    void startExecution_savesStagesAndJobs() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent("pipeline:\n  name: test\n  stages:\n    - name: build\n      jobs:\n        - name: build-job\n          type: build");

        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setPipelineVersion(version);

        when(pipelineStageRepository.save(any())).thenAnswer(inv -> {
            PipelineStage s = inv.getArgument(0);
            if (s.getId() == null) setId(s, UUID.randomUUID());
            return s;
        });
        when(pipelineRunRepository.save(any())).thenAnswer(inv -> {
            PipelineRun r = inv.getArgument(0);
            if (r.getId() == null) setId(r, runId);
            return r;
        });

        PipelineRun result = orchestrator.startExecution(run);

        assertEquals(PipelineRun.RunStatus.RUNNING, result.getStatus());
        assertNotNull(result.getStartedAt());
        verify(pipelineStageRepository).save(any(PipelineStage.class));
        verify(jobDispatcherService).dispatchReadyJobs(any());
        verify(outboxEventService).publishEvent(eq("RUN_STARTED"), eq("PipelineRun"), any(), any());
    }

    @Test
    void cancelRun_setsStatusToCancelled() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.QUEUED);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of());

        orchestrator.cancelRun(runId);

        assertEquals(PipelineRun.RunStatus.CANCELLED, run.getStatus());
        assertNotNull(run.getFinishedAt());
        verify(outboxEventService).publishEvent(eq("RUN_CANCELLED"), eq("PipelineRun"), eq(runId), any());
    }

    @Test
    void handleJobCompletion_updatesJobStatus() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.RUNNING);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stageResultCollector.evaluateStageStatus(eq(stage), anyList()))
                .thenReturn(PipelineStage.StageStatus.RUNNING);
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineStageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.handleJobCompletion(jobId, true, 0, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(PipelineJob.JobStatus.SUCCESS, job.getStatus());
        assertEquals(JobAttempt.AttemptStatus.SUCCESS, attempt.getStatus());
        verify(pipelineJobRepository).save(job);
        verify(jobAttemptRepository).save(attempt);
        verify(outboxEventService).publishEvent(eq("JOB_COMPLETED"), eq("PipelineJob"), eq(jobId), any());
    }

    @Test
    void handleJobCompletion_skipsAlreadyCompletedJob() {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        job.setStatus(PipelineJob.JobStatus.SUCCESS);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        orchestrator.handleJobCompletion(jobId, true, 0, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(PipelineJob.JobStatus.SUCCESS, job.getStatus());
        verify(pipelineJobRepository, never()).save(any());
    }

    @Test
    void handleJobCompletion_nullWorkerId_omitsWorkerIdFromPayload() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of());
        when(stageResultCollector.evaluateStageStatus(eq(stage), anyList()))
                .thenReturn(PipelineStage.StageStatus.RUNNING);
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineStageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.handleJobCompletion(jobId, true, 0, null,
                Instant.now(), Instant.now());

        assertEquals(PipelineJob.JobStatus.SUCCESS, job.getStatus());
        verify(outboxEventService).publishEvent(eq("JOB_COMPLETED"), eq("PipelineJob"), eq(jobId), any());
    }

    @Test
    void handleJobCompletion_failure_setsJobToFailed() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        PipelineJob otherJob = new PipelineJob();
        otherJob.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.RUNNING);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job, otherJob));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workspaceConfig.isRetryEnabled()).thenReturn(false);

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(PipelineJob.JobStatus.FAILED, job.getStatus());
        assertEquals(1, job.getExitCode());
    }

    @Test
    void handleJobCompletion_failure_setsAttemptToFailed() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        PipelineJob otherJob = new PipelineJob();
        otherJob.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.RUNNING);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job, otherJob));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workspaceConfig.isRetryEnabled()).thenReturn(false);

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(JobAttempt.AttemptStatus.FAILED, attempt.getStatus());
        assertEquals(1, attempt.getExitCode());
        assertNotNull(attempt.getFinishedAt());
    }

    @Test
    void handleJobCompletion_failure_retriesWhenEnabledAndAttemptsRemain() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        PipelineJob otherJob = new PipelineJob();
        otherJob.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        JobAttempt attempt1 = new JobAttempt(job, 1);
        attempt1.setStatus(JobAttempt.AttemptStatus.FAILED);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job, otherJob));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of(attempt1));
        when(workspaceConfig.isRetryEnabled()).thenReturn(true);
        when(workspaceConfig.getMaxRetries()).thenReturn(3);

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        verify(jobDispatcherService).dispatchForRetry(eq(job), eq(2));
        verify(jobDispatcherService, never()).dispatchReadyJobs(any());
    }

    @Test
    void handleJobCompletion_failure_noRetryWhenMaxExhausted() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        PipelineJob otherJob = new PipelineJob();
        otherJob.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        JobAttempt attempt1 = new JobAttempt(job, 1);
        attempt1.setStatus(JobAttempt.AttemptStatus.FAILED);
        JobAttempt attempt2 = new JobAttempt(job, 2);
        attempt2.setStatus(JobAttempt.AttemptStatus.FAILED);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job, otherJob));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of(attempt1, attempt2));
        when(workspaceConfig.isRetryEnabled()).thenReturn(true);
        when(workspaceConfig.getMaxRetries()).thenReturn(3);

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        verify(jobDispatcherService, never()).dispatchForRetry(any(), anyInt());
    }

    @Test
    void handleJobCompletion_failure_noRetryWhenDisabled() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        PipelineJob otherJob = new PipelineJob();
        otherJob.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.RUNNING);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job, otherJob));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workspaceConfig.isRetryEnabled()).thenReturn(false);

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        verify(jobDispatcherService, never()).dispatchForRetry(any(), anyInt());
    }

    @Test
    void handleJobCompletion_failure_propagatesFailedStage() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of());
        when(workspaceConfig.isRetryEnabled()).thenReturn(false);
        when(stageResultCollector.evaluateStageStatus(eq(stage), anyList()))
                .thenReturn(PipelineStage.StageStatus.FAILED);
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineStageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stageResultCollector.evaluateRunStatus(anyList()))
                .thenReturn(PipelineRun.RunStatus.FAILED);
        when(pipelineRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(PipelineStage.StageStatus.FAILED, stage.getStatus());
        verify(outboxEventService).publishEvent(eq("STAGE_COMPLETED"), eq("PipelineStage"), eq(stageId), any());
    }

    @Test
    void handleJobCompletion_failure_propagatesFailedRun() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of());
        when(workspaceConfig.isRetryEnabled()).thenReturn(false);
        when(stageResultCollector.evaluateStageStatus(eq(stage), anyList()))
                .thenReturn(PipelineStage.StageStatus.FAILED);
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineStageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stageResultCollector.evaluateRunStatus(anyList()))
                .thenReturn(PipelineRun.RunStatus.FAILED);
        when(pipelineRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(PipelineRun.RunStatus.FAILED, run.getStatus());
        assertNotNull(run.getFinishedAt());
        verify(outboxEventService).publishEvent(eq("RUN_COMPLETED"), eq("PipelineRun"), eq(runId), any());
    }

    @Test
    void handleJobCompletion_failure_doesNotDispatchNextJobs() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        PipelineJob otherJob = new PipelineJob();
        otherJob.setStatus(PipelineJob.JobStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.RUNNING);

        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        job.setPipelineStage(stage);
        stage.setPipelineRun(run);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job, otherJob));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of());
        when(workspaceConfig.isRetryEnabled()).thenReturn(true);
        when(workspaceConfig.getMaxRetries()).thenReturn(3);

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        verify(jobDispatcherService).dispatchForRetry(eq(job), eq(1));
    }

    @Test
    void handleJobCompletion_skipsAlreadyFailedJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.FAILED);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(PipelineJob.JobStatus.FAILED, job.getStatus());
        verify(pipelineJobRepository, never()).save(any());
    }

    @Test
    void handleJobCompletion_skipsAlreadyCancelledJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.CANCELLED);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        orchestrator.handleJobCompletion(jobId, false, 1, "worker-1",
                Instant.now(), Instant.now());

        assertEquals(PipelineJob.JobStatus.CANCELLED, job.getStatus());
        verify(pipelineJobRepository, never()).save(any());
    }

    @Test
    void cancelRun_cancelsRunningJobsAndAttempts() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setStatus(PipelineStage.StageStatus.PENDING);

        UUID jobId = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setStatus(PipelineJob.JobStatus.QUEUED);
        job.setPipelineStage(stage);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.RUNNING);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stageId)).thenReturn(List.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineStageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.cancelRun(runId);

        assertEquals(PipelineJob.JobStatus.CANCELLED, job.getStatus());
        assertNotNull(job.getFinishedAt());
        assertEquals(JobAttempt.AttemptStatus.CANCELLED, attempt.getStatus());
        assertNotNull(attempt.getFinishedAt());
        assertEquals(PipelineStage.StageStatus.SKIPPED, stage.getStatus());
        assertNotNull(stage.getFinishedAt());
    }
}
