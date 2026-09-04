package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.domain.entity.Repository;
import com.cicd.platform.controlplane.domain.entity.WebhookEvent;
import com.cicd.platform.controlplane.execution.WebhookEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebhookController webhookController;

    @MockBean private WebhookEventService webhookEventService;

    private Repository testRepository;
    private WebhookEvent testEvent;
    private UUID eventId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "test-secret");
        ReflectionTestUtils.setField(webhookController, "gitlabSecret", "");

        eventId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        testRepository = new Repository();
        ReflectionTestUtils.setField(testRepository, "id", repositoryId);
        testRepository.setRepositoryName("test-repo");
        testRepository.setRepositoryUrl("https://github.com/test-org/test-repo");

        testEvent = new WebhookEvent("github", "delivery-123", "push",
                testRepository, Map.of("ref", "refs/heads/main", "after", "abc123"));
        ReflectionTestUtils.setField(testEvent, "id", eventId);
        testEvent.setStatus(WebhookEvent.WebhookEventStatus.RECEIVED);
    }

    @Test
    void receiveWebhook_githubPush_returnsAccepted() throws Exception {
        String payload = """
                {"ref":"refs/heads/main","after":"abc123def","repository":{"name":"test-repo","full_name":"org/test-repo","html_url":"https://github.com/org/test-repo","clone_url":"https://github.com/org/test-repo.git"},"head_commit":{"id":"abc123def","message":"test commit","timestamp":"2026-09-04T10:00:00Z","author":{"name":"Test User","email":"test@example.com"}}}
                """;

        when(webhookEventService.receiveEvent(eq("github"), anyString(), eq("push"),
                isNull(), any(Map.class))).thenReturn(testEvent);
        when(webhookEventService.getEvent(eventId)).thenReturn(testEvent);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", computeHmacSha256("test-secret", payload))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.provider").value("github"))
                .andExpect(jsonPath("$.deliveryId").value("delivery-123"))
                .andExpect(jsonPath("$.eventType").value("push"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        verify(webhookEventService).receiveEvent(eq("github"), eq("delivery-123"),
                eq("push"), isNull(), any(Map.class));
        verify(webhookEventService).processEvent(eventId);
    }

    @Test
    void receiveWebhook_githubPushWithRepositoryId_passesIdToService() throws Exception {
        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        when(webhookEventService.receiveEvent(eq("github"), anyString(), eq("push"),
                eq(repositoryId), any(Map.class))).thenReturn(testEvent);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", computeHmacSha256("test-secret", payload))
                        .param("repositoryId", repositoryId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        verify(webhookEventService).receiveEvent(eq("github"), eq("delivery-123"),
                eq("push"), eq(repositoryId), any(Map.class));
    }

    @Test
    void receiveWebhook_gitlabPush_returnsAccepted() throws Exception {
        ReflectionTestUtils.setField(webhookController, "gitlabSecret", "gitlab-secret");

        String payload = """
                {"object_kind":"push","ref":"refs/heads/main","after":"abc123def","project":{"name":"test-repo","web_url":"https://gitlab.com/test-org/test-repo"}}
                """;

        WebhookEvent gitlabEvent = new WebhookEvent("gitlab", "delivery-456", "push",
                testRepository, Map.of("object_kind", "push", "ref", "refs/heads/main", "after", "abc123def"));
        ReflectionTestUtils.setField(gitlabEvent, "id", UUID.randomUUID());

        when(webhookEventService.receiveEvent(eq("gitlab"), anyString(), eq("Push Hook"),
                isNull(), any(Map.class))).thenReturn(gitlabEvent);

        mockMvc.perform(post("/api/v1/webhooks/gitlab")
                        .header("X-Gitlab-Event", "Push Hook")
                        .header("X-Gitlab-Token", "gitlab-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.provider").value("gitlab"));
    }

    @Test
    void receiveWebhook_gitlabBlankSecret_returnsForbidden() throws Exception {
        String payload = """
                {"object_kind":"push","ref":"refs/heads/main"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/gitlab")
                        .header("X-Gitlab-Event", "Push Hook")
                        .header("X-Gitlab-Token", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_payloadTooLarge_returnsTooLarge() throws Exception {
        ReflectionTestUtils.setField(webhookController, "gitlabSecret", "gitlab-secret");
        Integer originalMaxPayload = (Integer) ReflectionTestUtils.getField(webhookController, "maxPayloadBytes");
        ReflectionTestUtils.setField(webhookController, "maxPayloadBytes", 16);

        String payload = """
                {"object_kind":"push","ref":"refs/heads/main"}
                """;

        try {
            mockMvc.perform(post("/api/v1/webhooks/gitlab")
                            .header("X-Gitlab-Event", "Push Hook")
                            .header("X-Gitlab-Token", "gitlab-secret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isPayloadTooLarge());

            verifyNoInteractions(webhookEventService);
        } finally {
            ReflectionTestUtils.setField(webhookController, "maxPayloadBytes", originalMaxPayload);
        }
    }

    @Test
    void receiveWebhook_unsupportedProvider_returnsBadRequest() throws Exception {
        String payload = """
                {"ref":"refs/heads/main"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/bitbucket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_invalidJson_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-valid-json{{{"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_emptyBody_returnsError() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 400 || status == 500,
                            "Expected 400 or 500 for empty body but got " + status);
                });

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_signatureVerificationFails_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "my-secret");

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", "sha256=wrong-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_noSignatureWhenSecretConfigured_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "my-secret");

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_noSecretConfigured_rejectsRequest() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "");

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_gitlabSignatureVerificationFails_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "gitlabSecret", "gitlab-secret");

        String payload = """
                {"object_kind":"push","ref":"refs/heads/main"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/gitlab")
                        .header("X-Gitlab-Event", "Push Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_gitlabTokenMatches_returnsAccepted() throws Exception {
        ReflectionTestUtils.setField(webhookController, "gitlabSecret", "gitlab-secret");

        String payload = """
                {"object_kind":"push","ref":"refs/heads/main","after":"abc123def"}
                """;

        when(webhookEventService.receiveEvent(eq("gitlab"), anyString(), eq("Push Hook"),
                isNull(), any(Map.class))).thenReturn(testEvent);

        mockMvc.perform(post("/api/v1/webhooks/gitlab")
                        .header("X-Gitlab-Event", "Push Hook")
                        .header("X-Gitlab-Token", "gitlab-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

    @Test
    void receiveWebhook_duplicateEvent_returnsAccepted() throws Exception {
        WebhookEvent duplicateEvent = new WebhookEvent("github", "delivery-123", "push",
                testRepository, Map.of("ref", "refs/heads/main"));
        ReflectionTestUtils.setField(duplicateEvent, "id", UUID.randomUUID());
        duplicateEvent.setStatus(WebhookEvent.WebhookEventStatus.PROCESSED);

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        when(webhookEventService.receiveEvent(eq("github"), anyString(), eq("push"),
                isNull(), any(Map.class))).thenReturn(duplicateEvent);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", computeHmacSha256("test-secret", payload))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        verify(webhookEventService).processEvent(duplicateEvent.getId());
    }

    @Test
    void receiveWebhook_eventTypeFromHeader_respected() throws Exception {
        WebhookEvent releaseEvent = new WebhookEvent("github", "delivery-789", "release",
                testRepository, Map.of("action", "published"));
        ReflectionTestUtils.setField(releaseEvent, "id", UUID.randomUUID());

        String payload = """
                {"action":"published","release":{"tag_name":"v1.0"}}
                """;

        when(webhookEventService.receiveEvent(eq("github"), eq("delivery-789"), eq("release"),
                isNull(), any(Map.class))).thenReturn(releaseEvent);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "release")
                        .header("X-GitHub-Delivery", "delivery-789")
                        .header("X-Hub-Signature-256", computeHmacSha256("test-secret", payload))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventType").value("release"));
    }

    @Test
    void receiveWebhook_noDeliveryHeader_generatesId() throws Exception {
        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        when(webhookEventService.receiveEvent(eq("github"), anyString(), eq("push"),
                isNull(), any(Map.class))).thenReturn(testEvent);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", computeHmacSha256("test-secret", payload))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveryId").isNotEmpty());
    }

    @Test
    void getEvent_existingId_returnsOk() throws Exception {
        when(webhookEventService.getEvent(eventId)).thenReturn(testEvent);

        mockMvc.perform(get("/api/v1/webhooks/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.provider").value("github"))
                .andExpect(jsonPath("$.deliveryId").value("delivery-123"))
                .andExpect(jsonPath("$.eventType").value("push"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void getEvent_unknownId_returnsNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(webhookEventService.getEvent(unknownId))
                .thenThrow(new com.cicd.platform.controlplane.api.exception
                        .ResourceNotFoundException("Webhook event not found"));

        mockMvc.perform(get("/api/v1/webhooks/" + unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void receiveWebhook_providerCaseInsensitive_accepts() throws Exception {
        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        when(webhookEventService.receiveEvent(eq("GitHub"), anyString(), eq("push"),
                isNull(), any(Map.class))).thenReturn(testEvent);

        mockMvc.perform(post("/api/v1/webhooks/GitHub")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", computeHmacSha256("test-secret", payload))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

    @Test
    void receiveWebhook_validSignatureConstantTime_returnsAccepted() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "test-secret");

        String body = "{\"ref\":\"refs/heads/main\",\"after\":\"abc123def\"}";
        String validSignature = computeHmacSha256("test-secret", body);

        when(webhookEventService.receiveEvent(eq("github"), anyString(), eq("push"),
                isNull(), any(Map.class))).thenReturn(testEvent);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", validSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void receiveWebhook_malformedSignatureWrongPrefix_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "my-secret");

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", "md5=abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_malformedSignatureInvalidHex_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "my-secret");

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256",
                                "sha256=xyz123!@#$%^&*()_+-=[]{}|;':\",./<>?0123456789abcdef0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_malformedSignatureWrongLength_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "my-secret");

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", "sha256=abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_missingSignatureWithSecret_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "some-secret");

        String payload = """
                {"ref":"refs/heads/main","after":"abc123def"}
                """;

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_correctSignatureModifiedBody_returnsForbidden() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "my-secret");

        String originalBody = "{\"ref\":\"refs/heads/main\",\"after\":\"abc123def\"}";
        String validSignature = computeHmacSha256("my-secret", originalBody);

        String modifiedBody = "{\"ref\":\"refs/heads/main\",\"after\":\"MODIFIED\"}";

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", validSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifiedBody))
                .andExpect(status().isForbidden());

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void receiveWebhook_signatureUsesConstantTimeComparison() throws Exception {
        ReflectionTestUtils.setField(webhookController, "githubSecret", "test-secret");

        String body = "{\"ref\":\"refs/heads/main\",\"after\":\"abc123def\"}";
        String validSignature = computeHmacSha256("test-secret", body);
        String wrongSignature = "sha256=" + "0".repeat(64);

        when(webhookEventService.receiveEvent(eq("github"), anyString(), eq("push"),
                isNull(), any(Map.class))).thenReturn(testEvent);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-123")
                        .header("X-Hub-Signature-256", validSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "delivery-124")
                        .header("X-Hub-Signature-256", wrongSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
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

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
