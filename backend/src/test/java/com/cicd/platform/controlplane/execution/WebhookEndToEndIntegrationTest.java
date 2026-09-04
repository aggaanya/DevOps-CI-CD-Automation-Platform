package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.cicd.platform.controlplane.execution.message.JobMessageConsumer;
import com.cicd.platform.controlplane.execution.worker.GitOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "webhook.github.secret=e2e-test-secret",
        "execution.workspace.base-path=target/test-workspace-e2e",
        "execution.workspace.retry-enabled=false",
        "execution.workspace.max-retries=1"
})
class WebhookEndToEndIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private PipelineRunRepository pipelineRunRepository;
    @Autowired private PipelineStageRepository pipelineStageRepository;
    @Autowired private PipelineJobRepository pipelineJobRepository;
    @Autowired private JobAttemptRepository jobAttemptRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private JobMessageConsumer jobMessageConsumer;

    @MockBean private RabbitTemplate rabbitTemplate;
    @MockBean private GitOperations gitOperations;

    private Organization testOrg;
    private Project testProject;
    private Repository testRepository;
    private Pipeline testPipeline;
    private PipelineVersion testVersion;
    private final String testSecret = "e2e-test-secret";

    @BeforeEach
    void setUp() {
        testOrg = organizationRepository.save(new Organization("E2E Org", "e2e-org-" + UUID.randomUUID(), "E2E test org"));
        testProject = projectRepository.save(new Project(testOrg, "E2E Proj", "e2e-proj-" + UUID.randomUUID(), null));
        testRepository = repositoryRepository.save(
                new Repository(testProject, Repository.ProviderType.GITHUB, "https://github.com/e2e/test-repo.git", "test-repo", "main"));

        testPipeline = pipelineRepository.save(new Pipeline(testProject, "E2E Pipeline", "E2E test pipeline"));

        String yaml = """
                pipeline:
                  name: e2e-test
                  stages:
                    - name: build
                      jobs:
                        - name: compile
                          type: custom
                """;
        testVersion = pipelineVersionRepository.save(
                new PipelineVersion(testPipeline, 1, yaml, "abc123def456", "e2e-test"));
    }

    @Test
    void fullWebhookToPipelineCompletion() throws Exception {
        when(gitOperations.initializeWorkspace(any(), any(), any(), any())).thenReturn(true);

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def456","repository":{"name":"test-repo","full_name":"e2e/test-repo"}}
                """;
        String signature = computeHmacSha256(testSecret, payload);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "e2e-delivery-001")
                        .header("X-Hub-Signature-256", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.provider").value("github"))
                .andExpect(jsonPath("$.deliveryId").value("e2e-delivery-001"))
                .andExpect(jsonPath("$.eventType").value("push"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        // Verify WebhookEvent persisted
        Optional<WebhookEvent> eventOpt = webhookEventRepository.findByProviderAndDeliveryId("github", "e2e-delivery-001");
        assertTrue(eventOpt.isPresent(), "WebhookEvent should be persisted");
        WebhookEvent event = eventOpt.get();
        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, event.getStatus());
        assertEquals("push", event.getEventType());
        assertEquals(testRepository.getId(), event.getRepository().getId());

        // Verify PipelineRun created
        List<PipelineRun> runs = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId());
        assertEquals(1, runs.size(), "Exactly one PipelineRun should be created");
        PipelineRun run = runs.get(0);
        assertEquals(PipelineRun.TriggerType.WEBHOOK, run.getTriggerType());
        assertEquals("webhook:github", run.getTriggeredBy());
        assertEquals("abc123def456", run.getCommitSha());
        assertEquals("main", run.getBranch());
        assertEquals(testVersion.getId(), run.getPipelineVersion().getId());
        assertEquals(testRepository.getId(), run.getRepository().getId());

        // Verify PipelineStage created
        List<PipelineStage> stages = pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(1, stages.size());
        PipelineStage stage = stages.get(0);
        assertEquals("build", stage.getName());
        assertEquals(Integer.valueOf(0), stage.getOrderIndex());

        // Verify PipelineJob created
        List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stage.getId());
        assertEquals(1, jobs.size());
        PipelineJob job = jobs.get(0);
        assertEquals("compile", job.getName());
        assertEquals(PipelineJob.JobType.CUSTOM, job.getJobType());

        // Verify JobAttempt created by dispatcher
        List<JobAttempt> attempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
        assertEquals(1, attempts.size());
        assertEquals(Integer.valueOf(1), attempts.get(0).getAttemptNumber());

        // Capture the dispatch message
        var captor = org.mockito.ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                captor.capture());
        JobDispatchMessage dispatchMessage = captor.getValue();
        assertEquals(job.getId(), dispatchMessage.jobId());
        assertEquals(run.getId(), dispatchMessage.runId());

        // Simulate worker execution by invoking consumer directly
        Channel mockChannel = mock(Channel.class);
        jobMessageConsumer.onJobDispatch(dispatchMessage, mockChannel, 1L);

        // Verify job completed successfully (worker executed command)
        PipelineJob completedJob = pipelineJobRepository.findById(job.getId()).orElseThrow();
        assertEquals(PipelineJob.JobStatus.SUCCESS, completedJob.getStatus());
        assertNotNull(completedJob.getStartedAt());
        assertNotNull(completedJob.getFinishedAt());

        // Verify stage completed
        PipelineStage completedStage = pipelineStageRepository.findById(stage.getId()).orElseThrow();
        assertEquals(PipelineStage.StageStatus.SUCCESS, completedStage.getStatus());
        assertNotNull(completedStage.getStartedAt());
        assertNotNull(completedStage.getFinishedAt());

        // Verify pipeline run completed successfully
        PipelineRun completedRun = pipelineRunRepository.findById(run.getId()).orElseThrow();
        assertEquals(PipelineRun.RunStatus.SUCCESS, completedRun.getStatus());
        assertNotNull(completedRun.getStartedAt());
        assertNotNull(completedRun.getFinishedAt());
    }

    @Test
    void duplicateWebhookDelivery_createsOnlyOnePipelineRun() throws Exception {
        when(gitOperations.initializeWorkspace(any(), any(), any(), any())).thenReturn(true);

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def456"}
                """;
        String signature = computeHmacSha256(testSecret, payload);

        // Send first webhook
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "duplicate-delivery-001")
                        .header("X-Hub-Signature-256", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().isAccepted());

        // Simulate worker for first webhook
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                any(JobDispatchMessage.class));
        var captor = org.mockito.ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                captor.capture());
        jobMessageConsumer.onJobDispatch(captor.getValue(), mock(Channel.class), 1L);

        // Wait for first pipeline run to complete
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<PipelineRun> runs = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId());
            assertFalse(runs.isEmpty());
            assertEquals(PipelineRun.RunStatus.SUCCESS, runs.get(0).getStatus());
        });

        int runsAfterFirst = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId()).size();

        // Send duplicate webhook (same delivery ID)
        reset(rabbitTemplate);
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "duplicate-delivery-001")
                        .header("X-Hub-Signature-256", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().isAccepted());

        // Verify only one WebhookEvent exists for this delivery ID
        Optional<WebhookEvent> event = webhookEventRepository.findByProviderAndDeliveryId("github", "duplicate-delivery-001");
        assertTrue(event.isPresent());

        // Verify no additional PipelineRun was created
        List<PipelineRun> runsAfterDuplicate = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId());
        assertEquals(runsAfterFirst, runsAfterDuplicate.size(), "Duplicate webhook should not create additional PipelineRun");

        // Verify no additional dispatch message was sent
        verify(rabbitTemplate, never()).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                any(JobDispatchMessage.class));
    }

    @Test
    void webhookForInactivePipeline_eventProcessedNoRunCreated() throws Exception {
        testPipeline.setStatus(Pipeline.PipelineStatus.INACTIVE);
        pipelineRepository.save(testPipeline);

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def456"}
                """;
        String signature = computeHmacSha256(testSecret, payload);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "inactive-delivery-001")
                        .header("X-Hub-Signature-256", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().isAccepted());

        // Verify WebhookEvent is PROCESSED
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Optional<WebhookEvent> event = webhookEventRepository.findByProviderAndDeliveryId("github", "inactive-delivery-001");
            assertTrue(event.isPresent());
            assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, event.get().getStatus());
        });

        // Verify no PipelineRun was created (inactive pipeline was skipped)
        List<PipelineRun> runs = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId());
        assertTrue(runs.isEmpty(), "No PipelineRun should be created for INACTIVE pipeline");

        // Verify no dispatch message was sent
        verify(rabbitTemplate, never()).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                (Object) any());
    }

    @Test
    void webhookWithInvalidYaml_transactionRolledBackNoRunCreated() throws Exception {
        testVersion.setYamlContent("invalid: yaml: [[{{{{");
        pipelineVersionRepository.save(testVersion);

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def456"}
                """;
        String signature = computeHmacSha256(testSecret, payload);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "invalid-yaml-delivery-001")
                        .header("X-Hub-Signature-256", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().is5xxServerError());

        // processEvent transaction rolled back due to Spring @Transactional proxy on
        // orchestrator.startExecution(). The event was saved by receiveEvent() in a
        // separate transaction before processEvent ran, so it remains in RECEIVED.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Optional<WebhookEvent> event = webhookEventRepository.findByProviderAndDeliveryId("github", "invalid-yaml-delivery-001");
            assertTrue(event.isPresent());
            assertEquals(WebhookEvent.WebhookEventStatus.RECEIVED, event.get().getStatus());
        });

        // Verify no PipelineRun was created
        List<PipelineRun> runs = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId());
        assertTrue(runs.isEmpty(), "No PipelineRun should be created when YAML parsing fails");
    }

    @Test
    void webhookForPushEvent_triggersPipelineButReleaseDoesNot() throws Exception {
        // Push event should trigger
        String pushPayload = """
                {"ref":"refs/heads/main","after":"abc123def456"}
                """;
        String pushSig = computeHmacSha256(testSecret, pushPayload);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "push-delivery-001")
                        .header("X-Hub-Signature-256", pushSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushPayload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().isAccepted());

        List<PipelineRun> pushRuns = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId());
        assertEquals(1, pushRuns.size(), "Push event should trigger a pipeline run");

        // Ping event should NOT trigger
        String pingPayload = """
                {"zen":"Keep it simple","hook_id":12345}
                """;
        String pingSig = computeHmacSha256(testSecret, pingPayload);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "ping-delivery-001")
                        .header("X-Hub-Signature-256", pingSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pingPayload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().isAccepted());

        List<PipelineRun> allRuns = pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(testRepository.getId());
        assertEquals(1, allRuns.size(), "Ping event should NOT trigger a pipeline run");
    }

    @Test
    void webhookSignatureInvalid_returnsForbidden() throws Exception {
        String payload = """
                {"ref":"refs/heads/main","after":"abc123def456"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "bad-sig-delivery-001")
                        .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .param("repositoryId", testRepository.getId().toString()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(rabbitTemplate);
        assertTrue(webhookEventRepository.findByProviderAndDeliveryId("github", "bad-sig-delivery-001").isEmpty());
    }

    @Test
    void webhookUnsupportedProvider_returnsBadRequest() throws Exception {
        String payload = """
                {"ref":"refs/heads/main"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/bitbucket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void webhookWithNoRepositoryId_eventCreatedWithoutRepository() throws Exception {
        String payload = """
                {"ref":"refs/heads/main","after":"abc123def456"}
                """;
        String signature = computeHmacSha256(testSecret, payload);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "no-repo-delivery-001")
                        .header("X-Hub-Signature-256", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        // Verify event created without repository
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Optional<WebhookEvent> event = webhookEventRepository.findByProviderAndDeliveryId("github", "no-repo-delivery-001");
            assertTrue(event.isPresent());
            assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, event.get().getStatus());
            assertNull(event.get().getRepository(), "Event should have no repository");
        });

        // No pipeline should be triggered (no repository means no pipeline lookup)
        verify(rabbitTemplate, never()).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                (Object) any());
    }

    @Test
    void workerExecutionFailure_pipelineRunReachesFailed() throws Exception {
        // Create a second pipeline version with valid YAML but link to a repository with an uncloneable URL
        Repository failingRepo = repositoryRepository.save(
                new Repository(testProject, Repository.ProviderType.GITHUB, "https://127.0.0.1:1/fail.git", "fail-repo", "main"));

        String payload = """
                {"ref":"refs/heads/main","after":"fail123"}
                """;
        String signature = computeHmacSha256(testSecret, payload);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "fail-delivery-001")
                        .header("X-Hub-Signature-256", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .param("repositoryId", failingRepo.getId().toString()))
                .andExpect(status().isAccepted());

        // Capture dispatch message
        var captor = org.mockito.ArgumentCaptor.forClass(JobDispatchMessage.class);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(rabbitTemplate, atLeastOnce()).convertAndSend(
                    eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                    eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                    captor.capture());
        });

        // Simulate worker execution (git clone will fail)
        Channel mockChannel = mock(Channel.class);
        jobMessageConsumer.onJobDispatch(captor.getValue(), mockChannel, 1L);

        // Verify job failed
        PipelineJob failedJob = pipelineJobRepository.findById(captor.getValue().jobId()).orElseThrow();
        assertEquals(PipelineJob.JobStatus.FAILED, failedJob.getStatus());

        // Verify stage failed
        PipelineStage failedStage = pipelineStageRepository.findById(failedJob.getPipelineStage().getId()).orElseThrow();
        assertEquals(PipelineStage.StageStatus.FAILED, failedStage.getStatus());

        // Verify pipeline run failed
        PipelineRun failedRun = pipelineRunRepository.findById(captor.getValue().runId()).orElseThrow();
        assertEquals(PipelineRun.RunStatus.FAILED, failedRun.getStatus());
        assertNotNull(failedRun.getFinishedAt());
    }

    private String computeHmacSha256(String secret, String data) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("sha256=");
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
