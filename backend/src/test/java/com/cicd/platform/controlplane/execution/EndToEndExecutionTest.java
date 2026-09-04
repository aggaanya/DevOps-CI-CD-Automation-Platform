package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.cicd.platform.controlplane.execution.message.JobMessageConsumer;
import com.cicd.platform.controlplane.execution.worker.WorkerExecutor;
import com.cicd.platform.controlplane.execution.worker.WorkspaceManager;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EndToEndExecutionTest {

    @Mock private PipelineOrchestrator orchestrator;
    @Mock private WorkerExecutor workerExecutor;
    @Mock private WorkspaceManager workspaceManager;
    @Mock private WorkspaceConfig workspaceConfig;
    @Mock private PipelineJobRepository pipelineJobRepository;
    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private JobAttemptRepository jobAttemptRepository;
    @Mock private Channel channel;

    private JobMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new JobMessageConsumer(
                orchestrator, workerExecutor, workspaceManager, workspaceConfig,
                pipelineJobRepository, pipelineRunRepository, jobAttemptRepository);
    }

    @Test
    void fullFlow_dispatchToCompletion() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build-job", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, versionId, "build-job", "BUILD",
                "https://user:secret@github.com/org/repo.git", "main", "abc123",
                1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(workspaceConfig.getWorkerId()).thenReturn("worker-e2e");
        when(workspaceConfig.getTimeoutSeconds()).thenReturn(3600L);
        when(workspaceManager.createWorkspace(runId, jobId))
                .thenReturn(Path.of("/tmp/e2e-ws/" + runId + "/" + jobId));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/e2e-ws/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/e2e-ws/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/e2e-ws/artifacts"));
        when(workerExecutor.executeJob(any())).thenReturn(true);
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of());

        consumer.onJobDispatch(message, channel, 1L);

        assertEquals(PipelineJob.JobStatus.RUNNING, job.getStatus());
        assertEquals("worker-e2e", job.getWorkerId());
        assertNotNull(job.getStartedAt());

        verify(workspaceManager).createWorkspace(runId, jobId);
        verify(workerExecutor).executeJob(any(ExecutionContext.class));
        verify(orchestrator).handleJobCompletion(eq(jobId), eq(true), eq(0),
                eq("worker-e2e"), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void fullFlow_jobFailure_propagatesCorrectly() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "test-job", PipelineJob.JobType.TEST);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "test-job", "TEST",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(workspaceConfig.getWorkerId()).thenReturn("worker-1");
        when(workspaceConfig.getTimeoutSeconds()).thenReturn(600L);
        when(workspaceManager.createWorkspace(runId, jobId))
                .thenReturn(Path.of("/tmp/ws"));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/ws/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/ws/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/ws/artifacts"));
        when(workerExecutor.executeJob(any())).thenReturn(false);
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of());

        consumer.onJobDispatch(message, channel, 1L);

        assertEquals(PipelineJob.JobStatus.RUNNING, job.getStatus());

        ArgumentCaptor<UUID> jobIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orchestrator).handleJobCompletion(jobIdCaptor.capture(), eq(false), eq(1),
                anyString(), any(), any());
        assertEquals(jobId, jobIdCaptor.getValue());

        verify(channel).basicAck(1L, false);
    }

    @Test
    void fullFlow_withAttempt_lifecycleTracked() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "deploy-job", PipelineJob.JobType.DEPLOY);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "deploy-job", "DEPLOY",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(workspaceConfig.getWorkerId()).thenReturn("worker-1");
        when(workspaceConfig.getTimeoutSeconds()).thenReturn(3600L);
        when(workspaceManager.createWorkspace(runId, jobId))
                .thenReturn(Path.of("/tmp/ws"));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/ws/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/ws/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/ws/artifacts"));
        when(workerExecutor.executeJob(any())).thenReturn(true);
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        consumer.onJobDispatch(message, channel, 1L);

        assertEquals(JobAttempt.AttemptStatus.SUCCESS, attempt.getStatus());
        assertNotNull(attempt.getFinishedAt());
        assertNotNull(attempt.getLogsLocation());
        assertEquals(0, attempt.getExitCode());

        verify(jobAttemptRepository, atLeast(2)).save(any(JobAttempt.class));
    }

    @Test
    void fullFlow_jobFailureWithAttempt_attemptMarkedFailed() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "test-job", PipelineJob.JobType.TEST);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "test-job", "TEST",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(workspaceConfig.getWorkerId()).thenReturn("worker-1");
        when(workspaceConfig.getTimeoutSeconds()).thenReturn(600L);
        when(workspaceManager.createWorkspace(runId, jobId)).thenReturn(Path.of("/tmp/ws"));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/ws/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/ws/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/ws/artifacts"));
        when(workerExecutor.executeJob(any())).thenReturn(false);
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        consumer.onJobDispatch(message, channel, 1L);

        assertEquals(JobAttempt.AttemptStatus.FAILED, attempt.getStatus());
        assertEquals(1, attempt.getExitCode());
        assertNotNull(attempt.getFinishedAt());
        assertNotNull(attempt.getLogsLocation());

        verify(orchestrator).handleJobCompletion(eq(jobId), eq(false), eq(1),
                eq("worker-1"), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void fullFlow_duplicateSuccessJob_skipped() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.SUCCESS);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "build", "BUILD",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        consumer.onJobDispatch(message, channel, 1L);

        verify(workerExecutor, never()).executeJob(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void fullFlow_duplicateFailedJob_skipped() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.FAILED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "build", "BUILD",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        consumer.onJobDispatch(message, channel, 1L);

        verify(workerExecutor, never()).executeJob(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void fullFlow_jobNotFound_nacksMessage() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "build", "BUILD",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.empty());

        consumer.onJobDispatch(message, channel, 1L);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void fullFlow_runNotFound_nacksMessage() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "build", "BUILD",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.empty());

        consumer.onJobDispatch(message, channel, 1L);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void fullFlow_cancelledRun_skipsExecution() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.CANCELLED);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "build", "BUILD",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        consumer.onJobDispatch(message, channel, 1L);

        verify(workerExecutor, never()).executeJob(any());
        verify(orchestrator, never()).handleJobCompletion(any(), anyBoolean(), anyInt(),
                anyString(), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void fullFlow_duplicateMessageSkipped() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "build", "BUILD",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        consumer.onJobDispatch(message, channel, 1L);

        verify(workerExecutor, never()).executeJob(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void fullFlow_workerException_nacksMessage() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "build", "BUILD",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(workspaceConfig.getWorkerId()).thenReturn("worker-1");
        when(workspaceConfig.getTimeoutSeconds()).thenReturn(3600L);
        when(workspaceManager.createWorkspace(runId, jobId))
                .thenReturn(Path.of("/tmp/ws"));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/ws/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/ws/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/ws/artifacts"));
        when(workerExecutor.executeJob(any()))
                .thenThrow(new RuntimeException("Worker crashed"));

        consumer.onJobDispatch(message, channel, 1L);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(orchestrator, never()).handleJobCompletion(any(), anyBoolean(), anyInt(),
                anyString(), any(), any());
    }

    @Test
    void fullFlow_attemptExitCodePropagated() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "scan-job", PipelineJob.JobType.SCAN);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        JobAttempt attempt = new JobAttempt(job, 1);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(), "scan-job", "SCAN",
                "", "main", "sha1", 1, 1, UUID.randomUUID());

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(workspaceConfig.getWorkerId()).thenReturn("worker-1");
        when(workspaceConfig.getTimeoutSeconds()).thenReturn(600L);
        when(workspaceManager.createWorkspace(runId, jobId)).thenReturn(Path.of("/tmp/ws"));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/ws/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/ws/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/ws/artifacts"));
        when(workerExecutor.executeJob(any())).thenReturn(false);
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of(attempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        consumer.onJobDispatch(message, channel, 1L);

        assertEquals(1, attempt.getExitCode());
        assertEquals(JobAttempt.AttemptStatus.FAILED, attempt.getStatus());
        verify(orchestrator).handleJobCompletion(eq(jobId), eq(false), eq(1),
                eq("worker-1"), any(), any());
        verify(channel).basicAck(1L, false);
    }
}
