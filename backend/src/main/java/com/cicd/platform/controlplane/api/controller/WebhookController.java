package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.domain.entity.WebhookEvent;
import com.cicd.platform.controlplane.execution.WebhookEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookEventService webhookEventService;

    @Value("${webhook.github.secret:}")
    private String githubSecret;

    @Value("${webhook.gitlab.secret:}")
    private String gitlabSecret;

    public WebhookController(WebhookEventService webhookEventService) {
        this.webhookEventService = webhookEventService;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<WebhookEventResponse> receiveWebhook(
            @PathVariable String provider,
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String githubDelivery,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String githubSignature,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String gitlabEvent,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String gitlabToken,
            @RequestBody String rawBody,
            @RequestParam(required = false) UUID repositoryId) {

        Map<String, Object> payload = com.cicd.platform.controlplane.pipeline.parser.PipelineYamlParser
                .safeParseJson(rawBody);

        if (payload == null) {
            log.warn("Failed to parse webhook payload for provider={}", provider);
            return ResponseEntity.badRequest().build();
        }

        if (!verifySignature(provider, rawBody, githubSignature, gitlabToken)) {
            log.warn("Invalid webhook signature for provider={}", provider);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String eventType = resolveEventType(provider, githubEvent, gitlabEvent, payload);
        String deliveryId = resolveDeliveryId(provider, githubDelivery, payload);

        log.info("Webhook received: provider={}, eventType={}, deliveryId={}", provider, eventType, deliveryId);

        WebhookEvent event = webhookEventService.receiveEvent(provider, deliveryId, eventType, repositoryId, payload);
        webhookEventService.processEvent(event.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookEventResponse.from(event));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WebhookEventResponse> getEvent(@PathVariable UUID id) {
        WebhookEvent event = webhookEventService.getEvent(id);
        return ResponseEntity.ok(WebhookEventResponse.from(event));
    }

    private boolean verifySignature(String provider, String rawBody,
                                     String githubSignature, String gitlabToken) {
        return switch (provider.toLowerCase()) {
            case "github" -> verifyGitHubSignature(rawBody, githubSignature);
            case "gitlab" -> verifyGitlabToken(gitlabToken);
            default -> true;
        };
    }

    private boolean verifyGitHubSignature(String rawBody, String signature) {
        if (githubSecret == null || githubSecret.isBlank()) {
            return true;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(githubSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + bytesToHex(hash);
            return constantTimeEquals(expected, signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to verify GitHub webhook signature", e);
            return false;
        }
    }

    private boolean verifyGitlabToken(String token) {
        if (gitlabSecret == null || gitlabSecret.isBlank()) {
            return true;
        }
        return gitlabSecret.equals(token);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String resolveEventType(String provider, String githubEvent, String gitlabEvent, Map<String, Object> payload) {
        return switch (provider.toLowerCase()) {
            case "github" -> githubEvent != null ? githubEvent : (String) payload.getOrDefault("action", "unknown");
            case "gitlab" -> gitlabEvent != null ? gitlabEvent : (String) payload.getOrDefault("object_kind", "unknown");
            default -> (String) payload.getOrDefault("event_type", "unknown");
        };
    }

    private String resolveDeliveryId(String provider, String githubDelivery, Map<String, Object> payload) {
        if (githubDelivery != null) {
            return githubDelivery;
        }
        return (String) payload.getOrDefault("delivery_id",
                payload.getOrDefault("webhook_id", UUID.randomUUID().toString()));
    }

    public record WebhookEventResponse(
            UUID id,
            String provider,
            String deliveryId,
            String eventType,
            UUID repositoryId,
            String status,
            String errorMessage
    ) {
        public static WebhookEventResponse from(WebhookEvent event) {
            return new WebhookEventResponse(
                    event.getId(),
                    event.getProvider(),
                    event.getDeliveryId(),
                    event.getEventType(),
                    event.getRepository() != null ? event.getRepository().getId() : null,
                    event.getStatus().name(),
                    event.getErrorMessage()
            );
        }
    }
}
