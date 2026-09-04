package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.execution.config.JobTriggerProperties;
import com.cicd.platform.controlplane.execution.JobTriggerService.TriggerRequest;
import com.cicd.platform.controlplane.execution.JobTriggerService.TriggerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobTriggerServiceTest {

    @Mock private RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JobTriggerProperties properties = new JobTriggerProperties();

    private JobTriggerService newService() {
        return new JobTriggerService(properties, rabbitTemplate, objectMapper);
    }

    @Test
    void trigger_publishesWorkerShapeJsonToCicdTopology() throws Exception {
        JobTriggerService svc = newService();

        TriggerResult result = svc.trigger(new TriggerRequest(
                "https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git",
                "abc123def", "main", "pipeline-remote.yml", Map.of("ENV", "azure"), Map.of("label", "e2e")));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("cicd.jobs.exchange"), eq("cicd.job.submitted"),
                messageCaptor.capture());

        Message message = messageCaptor.getValue();
        assertEquals("application/json", message.getMessageProperties().getContentType());
        assertNull(message.getMessageProperties().getContentEncoding());

        Map<?, ?> body = objectMapper.readValue(message.getBody(), Map.class);
        assertEquals("abc123def", body.get("commitSha"));
        assertEquals("https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git", body.get("repositoryUrl"));
        assertEquals("main", body.get("branch"));
        assertEquals("pipeline-remote.yml", body.get("pipelineFile"));
        assertEquals(Map.of("ENV", "azure"), body.get("environment"));
        assertEquals(Map.of("label", "e2e"), body.get("metadata"));
        assertNotNull(body.get("jobId"));
        assertNotNull(body.get("pipelineId"));
        assertNotNull(body.get("createdAt"));

        result.jobId().equals(body.get("jobId"));
        assertEquals("QUEUED", result.status());
    }

    @Test
    void trigger_appliesDefaults_whenOptionalFieldsMissing() throws Exception {
        JobTriggerService svc = newService();

        svc.trigger(new TriggerRequest("https://github.com/x/y.git", "deadbeef", null, null, null, null));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
        Map<?, ?> body = objectMapper.readValue(messageCaptor.getValue().getBody(), Map.class);
        assertEquals("main", body.get("branch"));
        assertEquals("pipeline.yml", body.get("pipelineFile"));
        assertEquals(Map.of(), body.get("environment"));
        assertEquals(Map.of(), body.get("metadata"));
    }

    @Test
    void trigger_missingRepositoryUrl_rejects() {
        JobTriggerService svc = newService();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.trigger(new TriggerRequest("", "abc123", "main", "pipeline.yml", null, null)));
        assertTrue(ex.getMessage().contains("repositoryUrl"));
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void trigger_missingCommitSha_rejects() {
        JobTriggerService svc = newService();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.trigger(new TriggerRequest("https://github.com/x/y.git", "  ", "main", "pipeline.yml", null, null)));
        assertTrue(ex.getMessage().contains("commitSha"));
        verifyNoInteractions(rabbitTemplate);
    }
}