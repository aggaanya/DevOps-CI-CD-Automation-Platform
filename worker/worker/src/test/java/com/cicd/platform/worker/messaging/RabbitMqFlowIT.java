package com.cicd.platform.worker.messaging;

import com.cicd.platform.worker.TestGitRepo;
import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.PipelineResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test: RabbitMQ → worker → git clone → exact commit
 * checkout → pipeline YAML → Maven build + tests → structured result.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RabbitMqFlowIT {

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management-alpine")
            .withExposedPorts(5672);

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        Path wsRoot = java.nio.file.Files.createTempDirectory("cicd-it-workspaces");
        registry.add("worker.workspace-root", () -> wsRoot.toString());
        registry.add("worker.command-timeout-ms", () -> 600000L);
        registry.add("worker.retry-enabled", () -> false);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private CachingConnectionFactory connectionFactory;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WorkerProperties props;

    @Test
    void endToEndPipelineSuccess() throws Exception {
        assumeTrue(isMavenAvailable(), "mvn required on PATH");
        Path repo = java.nio.file.Files.createTempDirectory("cicd-fixture-repo-");
        String sha = TestGitRepo.createMavenRepo(repo, true);

        ResultCapture capture = new ResultCapture("it.success." + UUID.randomUUID());
        try {
            String jobId = "it-success-" + UUID.randomUUID();
            publish(jobId, repo.toUri().toString(), sha, "pipeline.yml");

            PipelineResult result = capture.awaitResult(5, TimeUnit.MINUTES);

            assertNotNull(result, "worker must publish a result");
            assertEquals(jobId, result.jobId());
            assertEquals(JobStatus.SUCCESS, result.status());
            assertEquals(sha, result.commitSha());
            assertEquals("maven-build", result.stages().get(0).jobs().get(0).name());
            assertEquals(JobStatus.SUCCESS, result.stages().get(0).status());
        } finally {
            capture.stop();
        }
    }

    @Test
    void endToEndPipelineBuildFailure() throws Exception {
        assumeTrue(isMavenAvailable(), "mvn required on PATH");
        Path repo = java.nio.file.Files.createTempDirectory("cicd-fixture-repo-");
        String sha = TestGitRepo.createMavenRepo(repo, false);

        ResultCapture capture = new ResultCapture("it.failure." + UUID.randomUUID());
        try {
            String jobId = "it-failure-" + UUID.randomUUID();
            publish(jobId, repo.toUri().toString(), sha, "pipeline.yml");

            PipelineResult result = capture.awaitResult(5, TimeUnit.MINUTES);

            assertNotNull(result, "worker must publish a result");
            assertEquals(JobStatus.FAILED, result.status());
            assertEquals(sha, result.commitSha());
            assertEquals(JobStatus.FAILED, result.stages().get(0).status());
            assertTrue(result.stages().get(0).jobs().get(0).steps().get(0).status() == JobStatus.FAILED);
        } finally {
            capture.stop();
        }
    }

    @Test
    void malformedMessageGoesToDeadLetterQueue() throws Exception {
        rabbitTemplate.send(props.getRabbit().getJobsExchange(), props.getRabbit().getJobRoutingKey(),
                MessageBuilder.withBody("not-json{{{".getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json").build());

        Message deadLettered = rabbitTemplate.receive(props.getRabbit().getDeadLetterQueue(), 20_000);
        assertNotNull(deadLettered, "malformed message must be dead-lettered");
    }

    @Test
    void duplicateJobIsExecutedOnce() throws Exception {
        assumeTrue(isMavenAvailable(), "mvn required on PATH");
        Path repo = java.nio.file.Files.createTempDirectory("cicd-fixture-repo-");
        String sha = TestGitRepo.createMavenRepo(repo, true);

        ResultCapture capture = new ResultCapture("it.duplicate." + UUID.randomUUID());
        try {
            String jobId = "it-dup-" + UUID.randomUUID();
            publish(jobId, repo.toUri().toString(), sha, "pipeline.yml");
            publish(jobId, repo.toUri().toString(), sha, "pipeline.yml");

            PipelineResult result = capture.awaitResult(5, TimeUnit.MINUTES);

            assertNotNull(result, "worker must publish a result");
            assertEquals(JobStatus.SUCCESS, result.status());
            assertEquals(0, capture.countResultsWithin(5, TimeUnit.SECONDS),
                    "duplicate jobId must not be executed twice");
        } finally {
            capture.stop();
        }
    }

    @Test
    void missingPipelineYamlIsReportedAsFailed() throws Exception {
        Path repo = java.nio.file.Files.createTempDirectory("cicd-fixture-repo-");
        String sha = TestGitRepo.createMavenRepo(repo, true);

        ResultCapture capture = new ResultCapture("it.missing." + UUID.randomUUID());
        try {
            String jobId = "it-missing-" + UUID.randomUUID();
            publish(jobId, repo.toUri().toString(), sha, "does-not-exist.yml");

            PipelineResult result = capture.awaitResult(30, TimeUnit.SECONDS);

            assertNotNull(result, "worker must publish a result");
            assertEquals(JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("not found"));
        } finally {
            capture.stop();
        }
    }

    private void publish(String jobId, String repoUrl, String sha, String pipelineFile) throws Exception {
        PipelineJob job = new PipelineJob(jobId, "pipeline-" + jobId, repoUrl, sha,
                "main", pipelineFile, null, null, Instant.now());
        byte[] body = objectMapper.writeValueAsBytes(job);
        rabbitTemplate.send(props.getRabbit().getJobsExchange(), props.getRabbit().getJobRoutingKey(),
                MessageBuilder.withBody(body).setContentType("application/json").build());
    }

    private boolean isMavenAvailable() {
        List<String> command = osIsWindows()
                ? List.of("cmd.exe", "/c", "mvn", "-v")
                : List.of("mvn", "-v");
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean osIsWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private final class ResultCapture {

        private final SimpleMessageListenerContainer container;
        private final BlockingQueue<String> results = new LinkedBlockingQueue<>();

        ResultCapture(String queueName) {
            RabbitAdmin admin = new RabbitAdmin(connectionFactory);
            admin.declareQueue(new Queue(queueName, false));
            admin.declareBinding(new org.springframework.amqp.core.Binding(queueName,
                    org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                    props.getRabbit().getResultsExchange(), props.getRabbit().getResultRoutingKey(), null));
            container = new SimpleMessageListenerContainer(connectionFactory);
            container.setQueueNames(queueName);
            container.setMessageListener(msg -> results.add(new String(msg.getBody(), StandardCharsets.UTF_8)));
            container.start();
        }

        PipelineResult awaitResult(long timeout, TimeUnit unit) throws Exception {
            String body = results.poll(timeout, unit);
            if (body == null) {
                return null;
            }
            return objectMapper.readValue(body, PipelineResult.class);
        }

        int countResultsWithin(long timeout, TimeUnit unit) throws Exception {
            String extra = results.poll(timeout, unit);
            return extra == null ? 0 : 1;
        }

        void stop() {
            container.stop();
        }
    }
}
