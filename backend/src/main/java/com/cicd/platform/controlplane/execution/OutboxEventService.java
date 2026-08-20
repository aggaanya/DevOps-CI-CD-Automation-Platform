package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.OutboxEvent;
import com.cicd.platform.controlplane.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void publishEvent(String eventType, String aggregateType, UUID aggregateId, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent event = new OutboxEvent(eventType, aggregateType, aggregateId, payloadJson);
            outboxEventRepository.save(event);
            log.debug("Published outbox event: {} for {} {}", eventType, aggregateType, aggregateId);
        } catch (Exception e) {
            log.error("Failed to create outbox event: {} for {} {}", eventType, aggregateType, aggregateId, e);
        }
    }

    @Transactional
    public void markPublished(UUID eventId) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxEvent.OutboxEventStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);
        });
    }

    @Transactional
    public void markFailed(UUID eventId, String errorMessage) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxEvent.OutboxEventStatus.FAILED);
            event.setErrorMessage(errorMessage);
            outboxEventRepository.save(event);
        });
    }

    @Transactional(readOnly = true)
    public java.util.List<OutboxEvent> getPendingEvents() {
        return outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxEventStatus.PENDING);
    }
}
