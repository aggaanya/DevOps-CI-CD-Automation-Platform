package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaleDispatchRecoveryServiceTest {

    @Mock private PipelineJobRepository pipelineJobRepository;
    @Mock private JobAttemptRepository jobAttemptRepository;
    @Mock private JobDispatcherService jobDispatcherService;
    @Mock private RunSchedulerLockRegistry runLocks;

    private StaleDispatchRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new StaleDispatchRecoveryService(
                pipelineJobRepository, jobAttemptRepository, jobDispatcherService,
                runLocks, 1L);

        lenient().when(runLocks.withRunLock(any(UUID.class), Mockito.<Supplier<Object>>any()))
                .thenAnswer(inv -> {
                    Supplier<?> body = inv.getArgument(1);
                    return body.get();
                });
    }

    private void setId(Object entity, UUID id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private PipelineJob queuedJob(PipelineRun run) throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        PipelineStage stage = new PipelineStage();
        setId(stage, stageId);
        stage.setPipelineRun(run);
        PipelineJob job = new PipelineJob();
        setId(job, jobId);
        job.setName("compile");
        job.setStatus(PipelineJob.JobStatus.QUEUED);
        job.setPipelineStage(stage);
        return job;
    }

    private PipelineRun runningRun() throws Exception {
        PipelineRun run = new PipelineRun();
        setId(run, UUID.randomUUID());
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        return run;
    }

    @Test
    void queuedJob_withNoAttempts_isRedispatched() throws Exception {
        PipelineRun run = runningRun();
        PipelineJob job = queuedJob(run);
        when(pipelineJobRepository.findByStatusWithRun(PipelineJob.JobStatus.QUEUED))
                .thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of());

        recoveryService.recoverStaleDispatches();

        verify(jobDispatcherService).recoverStaleDispatch(run.getId(), job.getId());
    }

    @Test
    void queuedJob_withOnlyTerminalAttempt_isRedispatched() throws Exception {
        PipelineRun run = runningRun();
        PipelineJob job = queuedJob(run);
        JobAttempt failed = new JobAttempt(job, 1);
        failed.setStatus(JobAttempt.AttemptStatus.FAILED);
        when(pipelineJobRepository.findByStatusWithRun(PipelineJob.JobStatus.QUEUED))
                .thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of(failed));

        recoveryService.recoverStaleDispatches();

        verify(jobDispatcherService).recoverStaleDispatch(run.getId(), job.getId());
    }

    @Test
    void queuedJob_withRunningAttempt_isLeftAlone() throws Exception {
        PipelineRun run = runningRun();
        PipelineJob job = queuedJob(run);
        JobAttempt running = new JobAttempt(job, 1);
        running.setStatus(JobAttempt.AttemptStatus.RUNNING);
        when(pipelineJobRepository.findByStatusWithRun(PipelineJob.JobStatus.QUEUED))
                .thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of(running));

        recoveryService.recoverStaleDispatches();

        verify(jobDispatcherService, never()).recoverStaleDispatch(any(), any());
    }

    @Test
    void queuedJob_withPendingAttempt_isConsideredInFlight() throws Exception {
        PipelineRun run = runningRun();
        PipelineJob job = queuedJob(run);
        JobAttempt pending = new JobAttempt(job, 1);
        pending.setStatus(JobAttempt.AttemptStatus.PENDING);
        when(pipelineJobRepository.findByStatusWithRun(PipelineJob.JobStatus.QUEUED))
                .thenReturn(List.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of(pending));

        recoveryService.recoverStaleDispatches();

        verify(jobDispatcherService, never()).recoverStaleDispatch(any(), any());
    }

    @Test
    void queuedJob_inFinishedRun_isLeftAlone() throws Exception {
        PipelineRun run = new PipelineRun();
        setId(run, UUID.randomUUID());
        run.setStatus(PipelineRun.RunStatus.FAILED);
        PipelineJob job = queuedJob(run);
        when(pipelineJobRepository.findByStatusWithRun(PipelineJob.JobStatus.QUEUED))
                .thenReturn(List.of(job));

        recoveryService.recoverStaleDispatches();

        verify(jobDispatcherService, never()).recoverStaleDispatch(any(), any());
    }

    @Test
    void disabledSweep_doesNothing() throws Exception {
        StaleDispatchRecoveryService disabled = new StaleDispatchRecoveryService(
                pipelineJobRepository, jobAttemptRepository, jobDispatcherService,
                runLocks, 0L);

        disabled.recoverStaleDispatches();

        verify(pipelineJobRepository, never()).findByStatusWithRun(any());
    }
}