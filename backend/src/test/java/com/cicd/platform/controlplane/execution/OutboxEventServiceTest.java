package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.OutboxEvent;
import com.cicd.platform.controlplane.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private OutboxEventService outboxEventService;

    @Test
    void publishEvent_savesEvent() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("key", "value");

        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"key\":\"value\"}");
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxEventService.publishEvent("RUN_STARTED", "PipelineRun", aggregateId, payload);

        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void publishEvent_serializationFails_doesNotThrow() throws Exception {
        UUID aggregateId = UUID.randomUUID();

        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("json error"));

        assertDoesNotThrow(() ->
                outboxEventService.publishEvent("RUN_STARTED", "PipelineRun", aggregateId, new Object()));
    }

    @Test
    void markPublished_setsStatusAndTime() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent("TEST", "Aggregate", UUID.randomUUID(), "{}");
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxEventService.markPublished(eventId);

        assertEquals(OutboxEvent.OutboxEventStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
    }

    @Test
    void markFailed_setsStatusAndError() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent("TEST", "Aggregate", UUID.randomUUID(), "{}");
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxEventService.markFailed(eventId, "connection refused");

        assertEquals(OutboxEvent.OutboxEventStatus.FAILED, event.getStatus());
        assertEquals("connection refused", event.getErrorMessage());
    }

    @Test
    void markPublished_eventNotFound_doesNothing() {
        UUID eventId = UUID.randomUUID();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> outboxEventService.markPublished(eventId));

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void markFailed_eventNotFound_doesNothing() {
        UUID eventId = UUID.randomUUID();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> outboxEventService.markFailed(eventId, "error"));

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void getPendingEvents_returnsPendingList() {
        OutboxEvent event = new OutboxEvent("TEST", "Aggregate", UUID.randomUUID(), "{}");
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxEventStatus.PENDING))
                .thenReturn(List.of(event));

        List<OutboxEvent> result = outboxEventService.getPendingEvents();

        assertEquals(1, result.size());
        assertEquals("TEST", result.get(0).getEventType());
    }
}
