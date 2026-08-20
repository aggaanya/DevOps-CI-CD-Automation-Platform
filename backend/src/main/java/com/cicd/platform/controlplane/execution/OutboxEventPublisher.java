package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventService outboxEventService;
    private final RabbitTemplate rabbitTemplate;

    public OutboxEventPublisher(OutboxEventService outboxEventService, RabbitTemplate rabbitTemplate) {
        this.outboxEventService = outboxEventService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventService.getPendingEvents();
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Publishing {} pending outbox events", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                rabbitTemplate.convertAndSend(
                        "outbox.exchange",
                        "outbox.event",
                        event.getPayload(),
                        message -> {
                            message.getMessageProperties().setHeader("eventType", event.getEventType());
                            message.getMessageProperties().setHeader("aggregateType", event.getAggregateType());
                            message.getMessageProperties().setHeader("aggregateId", event.getAggregateId().toString());
                            return message;
                        }
                );
                outboxEventService.markPublished(event.getId());
                log.debug("Published outbox event: id={}, type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                outboxEventService.markFailed(event.getId(), e.getMessage());
                log.error("Failed to publish outbox event: id={}, type={}", event.getId(), event.getEventType(), e);
            }
        }
    }
}
