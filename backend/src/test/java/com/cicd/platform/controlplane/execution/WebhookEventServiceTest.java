package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookEventServiceTest {

    @Mock private WebhookEventRepository webhookEventRepository;
    @Mock private RepositoryRepository repositoryRepository;
    @Mock private PipelineVersionRepository pipelineVersionRepository;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private PipelineOrchestrator orchestrator;
    @InjectMocks private WebhookEventService webhookEventService;

    private Repository testRepository;
    private UUID repositoryId;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        repositoryId = UUID.randomUUID();
        eventId = UUID.randomUUID();

        testRepository = new Repository();
        ReflectionTestUtils.setField(testRepository, "id", repositoryId);
        testRepository.setRepositoryName("test-repo");
        testRepository.setRepositoryUrl("https://github.com/test-org/test-repo");
        testRepository.setProvider(Repository.ProviderType.GITHUB);
    }

    @Test
    void receiveEvent_newEvent_savesAndReturns() {
        Map<String, Object> payload = Map.of("ref", "refs/heads/main", "after", "abc123");

        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1")).thenReturn(Optional.empty());
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(testRepository));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> {
            WebhookEvent event = inv.getArgument(0);
            ReflectionTestUtils.setField(event, "id", eventId);
            return event;
        });

        WebhookEvent result = webhookEventService.receiveEvent("github", "delivery-1", "push",
                repositoryId, payload);

        assertEquals("github", result.getProvider());
        assertEquals("delivery-1", result.getDeliveryId());
        assertEquals("push", result.getEventType());
        assertEquals(testRepository, result.getRepository());
        assertEquals(WebhookEvent.WebhookEventStatus.RECEIVED, result.getStatus());

        verify(webhookEventRepository).findByProviderAndDeliveryId("github", "delivery-1");
        verify(webhookEventRepository).save(any(WebhookEvent.class));
    }

    @Test
    void receiveEvent_duplicateEvent_returnsExisting() {
        WebhookEvent existingEvent = new WebhookEvent("github", "delivery-1", "push",
                testRepository, Map.of());
        ReflectionTestUtils.setField(existingEvent, "id", UUID.randomUUID());
        existingEvent.setStatus(WebhookEvent.WebhookEventStatus.PROCESSED);

        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1"))
                .thenReturn(Optional.of(existingEvent));

        WebhookEvent result = webhookEventService.receiveEvent("github", "delivery-1", "push",
                repositoryId, Map.of());

        assertEquals(existingEvent, result);
        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void receiveEvent_duplicateEventNotFound_throwsException() {
        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1"))
                .thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(WebhookEvent.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent duplicate"));

        assertThrows(ResourceNotFoundException.class, () ->
                webhookEventService.receiveEvent("github", "delivery-1", "push",
                        null, Map.of()));
    }

    @Test
    void receiveEvent_repositoryIdProvided_looksUpRepository() {
        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1")).thenReturn(Optional.empty());
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(testRepository));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> {
            WebhookEvent event = inv.getArgument(0);
            ReflectionTestUtils.setField(event, "id", eventId);
            return event;
        });

        WebhookEvent result = webhookEventService.receiveEvent("github", "delivery-1", "push",
                repositoryId, Map.of());

        assertEquals(testRepository, result.getRepository());
        verify(repositoryRepository).findById(repositoryId);
    }

    @Test
    void receiveEvent_repositoryNotFound_throwsException() {
        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1")).thenReturn(Optional.empty());
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                webhookEventService.receiveEvent("github", "delivery-1", "push",
                        repositoryId, Map.of()));
    }

    @Test
    void receiveEvent_nullRepositoryId_repositoryNull() {
        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1")).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> {
            WebhookEvent event = inv.getArgument(0);
            ReflectionTestUtils.setField(event, "id", eventId);
            return event;
        });

        WebhookEvent result = webhookEventService.receiveEvent("github", "delivery-1", "push",
                null, Map.of());

        assertNull(result.getRepository());
        verifyNoInteractions(repositoryRepository);
    }

    @Test
    void processEvent_pushEvent_triggersPipeline() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "after", "abc123def",
                "repository", Map.of("name", "test-repo")
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        assertNotNull(result.getProcessedAt());
        verify(orchestrator).startExecution(any(PipelineRun.class));
    }

    @Test
    void processEvent_releaseEvent_triggersPipeline() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("release");
        Map<String, Object> payload = Map.of(
                "after", "def456",
                "repository", Map.of("name", "test-repo")
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verify(orchestrator).startExecution(any(PipelineRun.class));
    }

    @Test
    void processEvent_pushEventOnGitLab_triggersPipeline() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push_events");
        event.setProvider("gitlab");
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "after", "abc123def"
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verify(orchestrator).startExecution(any(PipelineRun.class));
    }

    @Test
    void processEvent_noRepository_doesNotTriggerPipeline() {
        WebhookEvent event = new WebhookEvent("github", "delivery-1", "push", null, Map.of());
        ReflectionTestUtils.setField(event, "id", eventId);
        event.setStatus(WebhookEvent.WebhookEventStatus.RECEIVED);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verifyNoInteractions(pipelineVersionRepository);
        verify(orchestrator, never()).startExecution(any());
    }

    @Test
    void processEvent_nonTriggeringEventType_doesNotTriggerPipeline() {
        WebhookEvent event = createWebhookEvent("pull_request");
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "after", "abc123def"
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verifyNoInteractions(pipelineVersionRepository);
        verify(orchestrator, never()).startExecution(any());
    }

    @Test
    void processEvent_noLinkedPipelines_skipsTriggering() {
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "after", "abc123def"
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of());

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verify(orchestrator, never()).startExecution(any());
    }

    @Test
    void processEvent_pipelineNotActive_skipsTriggering() {
        PipelineVersion version = createPipelineVersion();
        version.getPipeline().setStatus(Pipeline.PipelineStatus.INACTIVE);

        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "after", "abc123def"
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verifyNoInteractions(pipelineRunRepository);
    }

    @Test
    void processEvent_repositoryThrows_setsFailedStatus() {
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of("ref", "refs/heads/main", "after", "abc123");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenThrow(new RuntimeException("Database connection lost"));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.FAILED, result.getStatus());
        assertEquals("Database connection lost", result.getErrorMessage());
    }

    @Test
    void processEvent_eventNotFound_throwsException() {
        UUID unknownId = UUID.randomUUID();
        when(webhookEventRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                webhookEventService.processEvent(unknownId));
    }

    @Test
    void processEvent_extractsCommitShaFromAfter() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of(
                "after", "deadbeef123",
                "ref", "refs/heads/main"
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository).save(argThat(run -> "deadbeef123".equals(run.getCommitSha())));
    }

    @Test
    void processEvent_extractsBranchFromRef() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of(
                "after", "abc123",
                "ref", "refs/heads/feature/my-branch"
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository).save(argThat(run -> "feature/my-branch".equals(run.getBranch())));
    }

    @Test
    void processEvent_extractsBranchFromTagRef() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of(
                "after", "abc123",
                "ref", "refs/tags/v1.0.0"
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository).save(argThat(run -> "v1.0.0".equals(run.getBranch())));
    }

    @Test
    void processEvent_noRefInPayload_defaultsToMain() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of("after", "abc123");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository).save(argThat(run -> "main".equals(run.getBranch())));
    }

    @Test
    void processEvent_noAfterKey_fallsBackToCommitsList() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> commit = Map.of("id", "commit-from-list");
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "commits", List.of(commit)
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository).save(argThat(run -> "commit-from-list".equals(run.getCommitSha())));
    }

    @Test
    void processEvent_emptyCommitsList_unknownSha() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "commits", List.of()
        );
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository).save(argThat(run -> "unknown".equals(run.getCommitSha())));
    }

    @Test
    void processEvent_setsTriggerTypeWebhook() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of("after", "abc123", "ref", "refs/heads/main");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository).save(argThat(run ->
                run.getTriggerType() == PipelineRun.TriggerType.WEBHOOK &&
                "webhook:github".equals(run.getTriggeredBy())));
    }

    @Test
    void getEvent_existingEvent_returnsEvent() {
        WebhookEvent event = createWebhookEvent("push");
        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        WebhookEvent result = webhookEventService.getEvent(eventId);

        assertEquals(event, result);
    }

    @Test
    void getEvent_unknownId_throwsException() {
        UUID unknownId = UUID.randomUUID();
        when(webhookEventRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                webhookEventService.getEvent(unknownId));
    }

    @Test
    void processEvent_multiplePipelineVersions_triggersAll() {
        PipelineVersion version1 = createPipelineVersion();
        UUID version2Id = UUID.randomUUID();
        PipelineVersion version2 = createPipelineVersion();
        ReflectionTestUtils.setField(version2, "id", version2Id);

        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of("after", "abc123", "ref", "refs/heads/main");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version1.getId(), version2Id));
        when(pipelineVersionRepository.findById(version1.getId())).thenReturn(Optional.of(version1));
        when(pipelineVersionRepository.findById(version2Id)).thenReturn(Optional.of(version2));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        verify(orchestrator, times(2)).startExecution(any(PipelineRun.class));
    }

    @Test
    void processEvent_gitlabNonTriggeringEvent_doesNotTrigger() {
        WebhookEvent event = createWebhookEvent("merge_request");
        event.setProvider("gitlab");
        Map<String, Object> payload = Map.of("after", "abc123", "ref", "refs/heads/main");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verifyNoInteractions(pipelineVersionRepository);
    }

    @Test
    void processEvent_alreadyProcessed_skipsReprocessing() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        event.setStatus(WebhookEvent.WebhookEventStatus.PROCESSED);
        event.setProcessedAt(java.time.Instant.now());
        Map<String, Object> payload = Map.of("after", "abc123", "ref", "refs/heads/main");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verify(webhookEventRepository, never()).save(any());
        verifyNoInteractions(pipelineVersionRepository);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void processEvent_calledTwiceOnProcessed_noSecondPipeline() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        event.setStatus(WebhookEvent.WebhookEventStatus.PROCESSED);
        event.setProcessedAt(java.time.Instant.now());
        Map<String, Object> payload = Map.of("after", "abc123", "ref", "refs/heads/main");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        webhookEventService.processEvent(eventId);
        webhookEventService.processEvent(eventId);

        verifyNoInteractions(pipelineVersionRepository);
        verifyNoInteractions(orchestrator);
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void receiveEvent_duplicate_returnsExistingWithoutSaving() {
        WebhookEvent existingEvent = createWebhookEvent("push");

        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1"))
                .thenReturn(Optional.of(existingEvent));

        WebhookEvent result = webhookEventService.receiveEvent("github", "delivery-1", "push",
                null, Map.of());

        assertEquals(existingEvent, result);
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void receiveEvent_differentDeliveryIds_processedIndependently() {
        WebhookEvent event1 = createWebhookEvent("push");
        WebhookEvent event2 = new WebhookEvent("github", "delivery-2", "push",
                testRepository, Map.of());
        ReflectionTestUtils.setField(event2, "id", UUID.randomUUID());

        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1"))
                .thenReturn(Optional.empty());
        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-2"))
                .thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> {
            WebhookEvent e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });

        WebhookEvent result1 = webhookEventService.receiveEvent("github", "delivery-1", "push",
                null, Map.of());
        WebhookEvent result2 = webhookEventService.receiveEvent("github", "delivery-2", "push",
                null, Map.of());

        assertNotEquals(result1.getDeliveryId(), result2.getDeliveryId());
        verify(webhookEventRepository, times(2)).save(any());
    }

    @Test
    void receiveEvent_sameDeliveryIdDifferentProviders_handledSeparately() {
        WebhookEvent githubEvent = new WebhookEvent("github", "delivery-1", "push",
                testRepository, Map.of());
        ReflectionTestUtils.setField(githubEvent, "id", UUID.randomUUID());

        when(webhookEventRepository.findByProviderAndDeliveryId("github", "delivery-1"))
                .thenReturn(Optional.empty());
        when(webhookEventRepository.findByProviderAndDeliveryId("gitlab", "delivery-1"))
                .thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> {
            WebhookEvent e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });

        WebhookEvent result1 = webhookEventService.receiveEvent("github", "delivery-1", "push",
                null, Map.of());
        WebhookEvent result2 = webhookEventService.receiveEvent("gitlab", "delivery-1", "push_events",
                null, Map.of());

        assertEquals("github", result1.getProvider());
        assertEquals("gitlab", result2.getProvider());
        verify(webhookEventRepository, times(2)).save(any());
    }

    @Test
    void processEvent_failedEvent_canBeReprocessed() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        event.setStatus(WebhookEvent.WebhookEventStatus.FAILED);
        Map<String, Object> payload = Map.of("after", "abc123", "ref", "refs/heads/main");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookEvent result = webhookEventService.processEvent(eventId);

        assertEquals(WebhookEvent.WebhookEventStatus.PROCESSED, result.getStatus());
        verify(orchestrator).startExecution(any(PipelineRun.class));
    }

    @Test
    void processEvent_duplicateDelivery_createsOnlyOnePipelineRun() {
        PipelineVersion version = createPipelineVersion();
        WebhookEvent event = createWebhookEvent("push");
        Map<String, Object> payload = Map.of("after", "abc123", "ref", "refs/heads/main");
        event.setPayload(payload);

        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.findLatestVersionIdsForRepository(repositoryId))
                .thenReturn(List.of(version.getId()));
        when(pipelineVersionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(pipelineRunRepository.save(any(PipelineRun.class))).thenAnswer(inv -> inv.getArgument(0));

        webhookEventService.processEvent(eventId);

        WebhookEvent processedEvent = createWebhookEvent("push");
        processedEvent.setStatus(WebhookEvent.WebhookEventStatus.PROCESSED);
        processedEvent.setPayload(payload);
        when(webhookEventRepository.findById(eventId)).thenReturn(Optional.of(processedEvent));

        webhookEventService.processEvent(eventId);

        verify(pipelineRunRepository, times(1)).save(any());
        verify(orchestrator, times(1)).startExecution(any());
    }

    private WebhookEvent createWebhookEvent(String eventType) {
        WebhookEvent event = new WebhookEvent("github", "delivery-1", eventType,
                testRepository, Map.of());
        ReflectionTestUtils.setField(event, "id", eventId);
        event.setStatus(WebhookEvent.WebhookEventStatus.RECEIVED);
        return event;
    }

    private PipelineVersion createPipelineVersion() {
        Pipeline pipeline = new Pipeline();
        ReflectionTestUtils.setField(pipeline, "id", UUID.randomUUID());
        pipeline.setName("test-pipeline");
        pipeline.setStatus(Pipeline.PipelineStatus.ACTIVE);

        PipelineVersion version = new PipelineVersion();
        ReflectionTestUtils.setField(version, "id", UUID.randomUUID());
        version.setPipeline(pipeline);
        version.setYamlContent("""
                pipeline:
                  name: test
                  stages:
                    - name: build
                      jobs:
                        - name: build-job
                          type: SHELL
                          script: echo "hello"
                """);
        return version;
    }
}
