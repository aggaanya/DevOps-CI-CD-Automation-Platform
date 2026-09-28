package com.cicd.platform.controlplane.execution.message;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.WorkerResult;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.WorkerResultRepository;
import com.cicd.platform.controlplane.execution.PipelineOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerResultConsumerTest {

    private static final String SUCCESS_RESULT = """
            {
              "jobId": "job-remote-123",
              "pipelineId": "pipeline-job-remote-123",
              "status": "SUCCESS",
              "workerId": "worker-azure-1",
              "repositoryUrl": "https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git",
              "commitSha": "841f6efa6d6b35a43bb12a1a62d5bc4c0ad23751",
              "branch": "main",
              "startedAt": "2026-09-05T10:00:00Z",
              "completedAt": "2026-09-05T10:00:01Z",
              "durationMs": 1200,
              "stages": [{"name": "build", "status": "SUCCESS", "durationMs": 1200}],
              "message": null
            }
            """;

    private static final UUID RUN_JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String RUN_RESULT = """
            {
              "jobId": "11111111-2222-3333-4444-555555555555",
              "pipelineId": "pipeline-run-1",
              "status": "SUCCESS",
              "workerId": "worker-docker",
              "repositoryUrl": "https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git",
              "commitSha": "841f6efa6d6b35a43bb12a1a62d5bc4c0ad23751",
              "branch": "main",
              "startedAt": "2026-09-05T10:00:00Z",
              "completedAt": "2026-09-05T10:00:01Z",
              "durationMs": 1200,
              "stages": [{"name": "build", "status": "SUCCESS", "durationMs": 1200}],
              "message": null
            }
            """;

    private WorkerResultRepository repository;
    private PipelineJobRepository pipelineJobRepository;
    private PipelineOrchestrator orchestrator;
    private Channel channel;
    private WorkerResultConsumer consumer;

    @BeforeEach
    void setUp() {
        repository = mock(WorkerResultRepository.class);
        pipelineJobRepository = mock(PipelineJobRepository.class);
        orchestrator = mock(PipelineOrchestrator.class);
        channel = mock(Channel.class);
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        consumer = new WorkerResultConsumer(objectMapper, repository, pipelineJobRepository, orchestrator);
    }

    private Message messageFor(String json) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(42L);
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void recordsValidResultAndAcks() throws Exception {
        when(repository.existsByJobId("job-remote-123")).thenReturn(false);

        assertDoesNotThrow(() -> consumer.onWorkerResult(messageFor(SUCCESS_RESULT), channel, 42L));

        verify(repository).save(any(WorkerResult.class));
        verify(channel).basicAck(42L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void ignoresDuplicateJobIdWithAck() throws Exception {
        when(repository.existsByJobIdAndStatusAndDurationMs("job-remote-123", "SUCCESS", 1200L))
                .thenReturn(true);

        consumer.onWorkerResult(messageFor(SUCCESS_RESULT), channel, 42L);

        verify(repository, never()).save(any(WorkerResult.class));
        verify(channel).basicAck(42L, false);
    }

    @Test
    void recordsRetryResultWithSameJobId() throws Exception {
        when(repository.existsByJobIdAndStatusAndDurationMs("job-remote-123", "FAILED", 1200L))
                .thenReturn(false);

        consumer.onWorkerResult(messageFor(SUCCESS_RESULT), channel, 42L);

        verify(repository).save(any(WorkerResult.class));
        verify(channel).basicAck(42L, false);
    }

    @Test
    void rejectsMalformedBodyWithoutRequeue() throws Exception {
        consumer.onWorkerResult(messageFor("{ not valid json"), channel, 42L);

        verify(repository, never()).save(any(WorkerResult.class));
        verify(channel).basicNack(42L, false, false);
    }

    @Test
    void rejectsResultWithoutJobId() throws Exception {
        consumer.onWorkerResult(messageFor("{\"status\":\"SUCCESS\",\"workerId\":\"w\"}"), channel, 42L);

        verify(repository, never()).save(any(WorkerResult.class));
        verify(channel).basicNack(42L, false, false);
    }

    @Test
    void rejectsWhenPersistenceFails() throws Exception {
        when(repository.existsByJobId("job-remote-123")).thenReturn(false);
        when(repository.save(any(WorkerResult.class)))
                .thenThrow(new IllegalStateException("db unavailable"));

        consumer.onWorkerResult(messageFor(SUCCESS_RESULT), channel, 42L);

        verify(channel).basicNack(42L, false, false);
    }

    @Test
    void advancesRunStateWhenJobBelongsToRun() throws Exception {
        when(repository.existsByJobId(RUN_JOB_ID.toString())).thenReturn(false);

        PipelineRun run = new PipelineRun();
        setId(run, UUID.fromString("99999999-8888-7777-6666-555555555555"));
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        PipelineStage stage = new PipelineStage();
        stage.setPipelineRun(run);
        PipelineJob job = new PipelineJob(stage, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.RUNNING);

        when(pipelineJobRepository.findById(RUN_JOB_ID)).thenReturn(Optional.of(job));

        consumer.onWorkerResult(messageFor(RUN_RESULT), channel, 42L);

        verify(orchestrator).handleJobCompletion(
                eq(RUN_JOB_ID), eq(true), eq(0), eq("worker-docker"),
                eq(Instant.parse("2026-09-05T10:00:00Z")), eq(Instant.parse("2026-09-05T10:00:01Z")));
        verify(channel).basicAck(42L, false);
    }

    @Test
    void doesNotAdvanceRunStateForNonUuidJobId() throws Exception {
        when(repository.existsByJobId("job-remote-123")).thenReturn(false);

        consumer.onWorkerResult(messageFor(SUCCESS_RESULT), channel, 42L);

        verify(orchestrator, never()).handleJobCompletion(
                any(), anyBoolean(), anyInt(), anyString(), any(), any());
        verify(channel).basicAck(42L, false);
    }

    @Test
    void doesNotAdvanceRunStateWhenJobHasNoRun() throws Exception {
        when(repository.existsByJobId(RUN_JOB_ID.toString())).thenReturn(false);

        PipelineJob job = new PipelineJob(null, "build", PipelineJob.JobType.BUILD);
        job.setStatus(PipelineJob.JobStatus.RUNNING);
        when(pipelineJobRepository.findById(RUN_JOB_ID)).thenReturn(Optional.of(job));

        consumer.onWorkerResult(messageFor(RUN_RESULT), channel, 42L);

        verify(orchestrator, never()).handleJobCompletion(
                any(), anyBoolean(), anyInt(), anyString(), any(), any());
        verify(channel).basicAck(42L, false);
    }

    private static void setId(PipelineRun run, UUID id) throws Exception {
        java.lang.reflect.Field f = PipelineRun.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(run, id);
    }
}