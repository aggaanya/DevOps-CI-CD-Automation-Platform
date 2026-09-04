package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "webhook_events", uniqueConstraints = {
        @UniqueConstraint(name = "uq_webhook_events_provider_delivery", columnNames = {"provider", "delivery_id"})
}, indexes = {
        @Index(name = "idx_webhook_events_repository_id", columnList = "repository_id"),
        @Index(name = "idx_webhook_events_status", columnList = "status"),
        @Index(name = "idx_webhook_events_received_at", columnList = "received_at DESC")
})
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(name = "delivery_id", nullable = false, length = 255)
    private String deliveryId;

    @Column(name = "event_type", nullable = false, length = 255)
    private String eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", foreignKey = @ForeignKey(name = "fk_webhook_events_repository"))
    private Repository repository;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private WebhookEventStatus status = WebhookEventStatus.RECEIVED;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        this.receivedAt = Instant.now();
    }

    public WebhookEvent() {}

    public WebhookEvent(String provider, String deliveryId, String eventType,
                        Repository repository, Map<String, Object> payload) {
        this.provider = provider;
        this.deliveryId = deliveryId;
        this.eventType = eventType;
        this.repository = repository;
        this.payload = payload;
    }

    public UUID getId() { return id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Repository getRepository() { return repository; }
    public void setRepository(Repository repository) { this.repository = repository; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public Instant getReceivedAt() { return receivedAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    public WebhookEventStatus getStatus() { return status; }
    public void setStatus(WebhookEventStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookEvent that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public enum WebhookEventStatus {
        RECEIVED, PROCESSING, PROCESSED, REJECTED, FAILED
    }
}
