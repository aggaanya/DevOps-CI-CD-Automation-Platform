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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobMessageConsumerTest {

    @InjectMocks
    private JobMessageConsumer consumer;

    @Mock
    private PipelineOrchestrator orchestrator;
    @Mock
    private WorkerExecutor workerExecutor;
    @Mock
    private WorkspaceManager workspaceManager;
    @Mock
    private WorkspaceConfig workspaceConfig;
    @Mock
    private PipelineJobRepository pipelineJobRepository;
    @Mock
    private PipelineRunRepository pipelineRunRepository;
    @Mock
    private JobAttemptRepository jobAttemptRepository;
    @Mock
    private Channel channel;

    private JobDispatchMessage buildMessage(UUID jobId, UUID runId) {
        return new JobDispatchMessage(jobId, runId, UUID.randomUUID(),
                "build-job", "BUILD", "https://repo.git", "main", "sha1", 1, 1, UUID.randomUUID());
    }

    @Test
    void onJobDispatch_success_acksMessage() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        JobDispatchMessage msg = buildMessage(jobId, runId);

        PipelineJob job = new PipelineJob(null, "build-job", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.QUEUED);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(workspaceConfig.getWorkerId()).thenReturn("worker-1");
        when(workspaceConfig.getTimeoutSeconds()).thenReturn(3600L);
        when(workspaceManager.createWorkspace(runId, jobId)).thenReturn(Path.of("/tmp/ws"));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/ws/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/ws/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/ws/artifacts"));
        when(workerExecutor.executeJob(any())).thenReturn(true);
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).thenReturn(List.of());

        consumer.onJobDispatch(msg, channel, 1L);

        assertEquals(PipelineJob.JobStatus.RUNNING, job.getStatus());
        verify(orchestrator).handleJobCompletion(eq(jobId), eq(true), eq(0), anyString(), any(), any());
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void onJobDispatch_failure_nacksMessage() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        JobDispatchMessage msg = buildMessage(jobId, runId);

        when(pipelineJobRepository.findById(jobId))
                .thenThrow(new RuntimeException("DB unavailable"));

        consumer.onJobDispatch(msg, channel, 2L);

        verify(channel).basicNack(2L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void onJobDispatch_cancelledRun_skips() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        JobDispatchMessage msg = buildMessage(jobId, runId);

        PipelineJob job = new PipelineJob(null, "build-job", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.QUEUED);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.CANCELLED);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        consumer.onJobDispatch(msg, channel, 3L);

        verify(channel).basicAck(3L, false);
        verify(workerExecutor, never()).executeJob(any());
    }

    @Test
    void onJobDispatch_alreadyRunning_skipsDuplicate() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        JobDispatchMessage msg = buildMessage(jobId, runId);

        PipelineJob job = new PipelineJob(null, "build-job", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.RUNNING);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        consumer.onJobDispatch(msg, channel, 4L);

        verify(channel).basicAck(4L, false);
        verify(workerExecutor, never()).executeJob(any());
    }
}
