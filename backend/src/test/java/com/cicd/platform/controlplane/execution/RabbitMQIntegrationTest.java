package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.cicd.platform.controlplane.execution.message.JobMessageConsumer;
import com.cicd.platform.controlplane.execution.worker.WorkerExecutor;
import com.cicd.platform.controlplane.execution.worker.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * In-process dispatch -> consume flow against an isolated RabbitMQ broker.
 *
 * <p>Broker isolation is deliberate: this test must never touch the shared
 * production broker where the running backend is a live competing consumer on
 * {@code pipeline-jobs} (which would steal the dispatched message and nack it,
 * leaving this test's mock {@link WorkerExecutor} uninvoked). Testcontainers
 * gives a deterministic broker per suite run and is Docker-only, so the test
 * works identically on CI (ubuntu runners ship Docker) and locally.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RabbitMQIntegrationTest {

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage("(?s).*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private JobMessageConsumer jobMessageConsumer;



    @MockBean
    private PipelineJobRepository pipelineJobRepository;

    @MockBean
    private PipelineRunRepository pipelineRunRepository;

    @MockBean
    private JobAttemptRepository jobAttemptRepository;

    @MockBean
    private PipelineOrchestrator orchestrator;

    @MockBean
    private WorkerExecutor workerExecutor;

    @MockBean
    private WorkspaceManager workspaceManager;

    @BeforeEach
    void setUp() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
            }
        }

        reset(
                pipelineJobRepository,
                pipelineRunRepository,
                jobAttemptRepository,
                orchestrator,
                workerExecutor,
                workspaceManager
        );

        rabbitTemplate.execute(channel -> {
            channel.queuePurge(ExecutionConstants.JOB_DISPATCH_QUEUE);
            return null;
        });
    }

    @Test
    void dispatchAndConsumeJob_createsAttemptAndProcessesJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();

        PipelineJob job = new PipelineJob(null, "build-job", PipelineJob.JobType.BUILD);
        java.lang.reflect.Field idField = PipelineJob.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(job, jobId);
        job.setStatus(PipelineJob.JobStatus.QUEUED);

        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);

        when(pipelineJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineJobRepository.transitionStatus(
                eq(jobId),
                eq(PipelineJob.JobStatus.QUEUED),
                eq(PipelineJob.JobStatus.RUNNING),
                anyString(),
                any())).thenReturn(1);
        when(workspaceManager.createWorkspace(runId, jobId))
                .thenReturn(Path.of("/tmp/test-workspace"));
        when(workspaceManager.getWorkDir(any())).thenReturn(Path.of("/tmp/test-workspace/work"));
        when(workspaceManager.getLogsDir(any())).thenReturn(Path.of("/tmp/test-workspace/logs"));
        when(workspaceManager.getArtifactsDir(any())).thenReturn(Path.of("/tmp/test-workspace/artifacts"));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))
                .thenReturn(List.of());
        when(workerExecutor.executeJob(any())).thenReturn(true);

        JobDispatchMessage message = new JobDispatchMessage(
                jobId, runId, UUID.randomUUID(),
                "build-job", "BUILD", "https://repo.git", "main", "abc123",
                1, 1, workerId);

        rabbitTemplate.convertAndSend(
                ExecutionConstants.JOB_DISPATCH_EXCHANGE,
                ExecutionConstants.JOB_DISPATCH_ROUTING_KEY,
                message);

        Thread.sleep(2000);

        verify(workerExecutor, timeout(5000).times(1)).executeJob(any());
    }
}
