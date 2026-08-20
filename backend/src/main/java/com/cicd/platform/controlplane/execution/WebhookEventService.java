package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class WebhookEventService {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final RepositoryRepository repositoryRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineOrchestrator orchestrator;

    public WebhookEventService(WebhookEventRepository webhookEventRepository,
                               RepositoryRepository repositoryRepository,
                               PipelineVersionRepository pipelineVersionRepository,
                               PipelineRepository pipelineRepository,
                               PipelineRunRepository pipelineRunRepository,
                               PipelineOrchestrator orchestrator) {
        this.webhookEventRepository = webhookEventRepository;
        this.repositoryRepository = repositoryRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.orchestrator = orchestrator;
    }

    public WebhookEvent receiveEvent(String provider, String deliveryId, String eventType,
                                     UUID repositoryId, Map<String, Object> payload) {
        if (webhookEventRepository.existsByProviderAndDeliveryId(provider, deliveryId)) {
            log.info("Duplicate webhook event ignored: provider={}, deliveryId={}", provider, deliveryId);
            return webhookEventRepository.findByProviderAndDeliveryId(provider, deliveryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Webhook event not found"));
        }

        Repository repository = null;
        if (repositoryId != null) {
            repository = repositoryRepository.findById(repositoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id: " + repositoryId));
        }

        WebhookEvent event = new WebhookEvent(provider, deliveryId, eventType, repository, payload);
        event.setStatus(WebhookEvent.WebhookEventStatus.RECEIVED);
        event = webhookEventRepository.save(event);

        log.info("Webhook event received: provider={}, eventType={}, deliveryId={}, repositoryId={}",
                provider, eventType, deliveryId, repositoryId);

        return event;
    }

    public WebhookEvent processEvent(UUID eventId) {
        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook event not found with id: " + eventId));

        event.setStatus(WebhookEvent.WebhookEventStatus.PROCESSING);
        webhookEventRepository.save(event);

        try {
            log.info("Processing webhook event: id={}, eventType={}", eventId, event.getEventType());

            if (shouldTriggerPipeline(event)) {
                triggerPipelineFromWebhook(event);
            }

            event.setStatus(WebhookEvent.WebhookEventStatus.PROCESSED);
            event.setProcessedAt(java.time.Instant.now());
            webhookEventRepository.save(event);
        } catch (Exception e) {
            event.setStatus(WebhookEvent.WebhookEventStatus.FAILED);
            event.setErrorMessage(e.getMessage());
            webhookEventRepository.save(event);
            log.error("Failed to process webhook event: id={}", eventId, e);
        }

        return event;
    }

    private boolean shouldTriggerPipeline(WebhookEvent event) {
        if (event.getRepository() == null) {
            return false;
        }
        String type = event.getEventType();
        return switch (event.getProvider().toLowerCase()) {
            case "github" -> "push".equals(type) || "release".equals(type);
            case "gitlab" -> "push_events".equals(type) || "tag_push_events".equals(type);
            default -> "push".equals(type);
        };
    }

    @SuppressWarnings("unchecked")
    private void triggerPipelineFromWebhook(WebhookEvent event) {
        Repository repository = event.getRepository();
        Map<String, Object> payload = event.getPayload();

        List<UUID> pipelineVersionIds = pipelineVersionRepository
                .findLatestVersionIdsForRepository(repository.getId());

        if (pipelineVersionIds.isEmpty()) {
            log.info("No pipeline versions linked to repository {}, skipping webhook trigger",
                    repository.getId());
            return;
        }

        for (UUID versionId : pipelineVersionIds) {
            PipelineVersion version = pipelineVersionRepository.findById(versionId).orElse(null);
            if (version == null) continue;

            Pipeline pipeline = version.getPipeline();
            if (pipeline.getStatus() != Pipeline.PipelineStatus.ACTIVE) {
                log.info("Pipeline {} is not ACTIVE, skipping trigger", pipeline.getId());
                continue;
            }

            String commitSha = extractCommitSha(payload);
            String branch = extractBranch(payload);

            PipelineRun run = new PipelineRun(version, repository, commitSha, branch,
                    PipelineRun.TriggerType.WEBHOOK, "webhook:" + event.getProvider());
            run.setStatus(PipelineRun.RunStatus.QUEUED);
            run = pipelineRunRepository.save(run);

            log.info("Triggered pipeline run {} from webhook event {}",
                    run.getId(), event.getId());

            orchestrator.startExecution(run);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractCommitSha(Map<String, Object> payload) {
        if (payload.containsKey("after")) {
            return (String) payload.get("after");
        }
        Object commits = payload.get("commits");
        if (commits instanceof List<?> commitList && !commitList.isEmpty()) {
            Object first = commitList.get(0);
            if (first instanceof Map<?, ?> commitMap) {
                return (String) commitMap.get("id");
            }
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private String extractBranch(Map<String, Object> payload) {
        String ref = (String) payload.get("ref");
        if (ref != null && ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref != null && ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return "main";
    }

    @Transactional(readOnly = true)
    public WebhookEvent getEvent(UUID eventId) {
        return webhookEventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook event not found with id: " + eventId));
    }
}
