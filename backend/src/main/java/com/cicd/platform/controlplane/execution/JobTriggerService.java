package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.execution.config.JobTriggerProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Submits an ad-hoc pipeline execution job to the standalone worker topology
 * ({@code cicd.jobs.exchange}). The job message mirrors the wire format the
 * worker deserializes into {@code PipelineJob} (jobId, pipelineId,
 * repositoryUrl, commitSha, branch, pipelineFile, environment, metadata,
 * createdAt).
 *
 * <p>Atomicity note: RabbitMQ is at-least-once; the worker deduplicates by
 * {@code jobId}, so callers may retry safely.</p>
 */
@Service
public class JobTriggerService {

    private static final Logger log = LoggerFactory.getLogger(JobTriggerService.class);

    private final JobTriggerProperties properties;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public JobTriggerService(JobTriggerProperties properties,
                             RabbitTemplate rabbitTemplate,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    public TriggerResult trigger(TriggerRequest request) {
        if (request.repositoryUrl == null || request.repositoryUrl.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl is required");
        }
        if (request.commitSha == null || request.commitSha.isBlank()) {
            throw new IllegalArgumentException("commitSha is required");
        }

        String jobId = "job-" + UUID.randomUUID();
        String pipelineId = "pipeline-" + jobId;
        String branch = request.branch != null && !request.branch.isBlank()
                ? request.branch : "main";
        String pipelineFile = request.pipelineFile != null && !request.pipelineFile.isBlank()
                ? request.pipelineFile : "pipeline.yml";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("pipelineId", pipelineId);
        body.put("repositoryUrl", request.repositoryUrl);
        body.put("commitSha", request.commitSha);
        body.put("branch", branch);
        body.put("pipelineFile", pipelineFile);
        body.put("environment", request.environment != null ? request.environment : Map.of());
        body.put("metadata", request.metadata != null ? request.metadata : Map.of());
        body.put("createdAt", Instant.now().toString());

        Message message = MessageBuilder.withBody(serialize(body))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.send(properties.getExchange(), properties.getRoutingKey(), message);

        log.info("[JOB_TRIGGERED] jobId={}, pipelineId={}, repositoryUrl={}, sha={}, branch={}, file={}",
                jobId, pipelineId, request.repositoryUrl, request.commitSha, branch, pipelineFile);

        return new TriggerResult(jobId, pipelineId, request.repositoryUrl, request.commitSha,
                branch, pipelineFile, "QUEUED");
    }

    private byte[] serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize trigger job", e);
        }
    }

    public record TriggerRequest(
            String repositoryUrl,
            String commitSha,
            String branch,
            String pipelineFile,
            Map<String, String> environment,
            Map<String, Object> metadata
    ) {
    }

    public record TriggerResult(
            String jobId,
            String pipelineId,
            String repositoryUrl,
            String commitSha,
            String branch,
            String pipelineFile,
            String status
    ) {
    }
}