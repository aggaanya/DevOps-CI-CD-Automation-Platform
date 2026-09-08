package com.cicd.platform.worker.messaging;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.PipelineResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes structured {@link PipelineResult} objects to the results exchange
 * and re-publishes jobs that need a retry (with backoff through the delay
 * queue).
 *
 * <p>Results are published as plain JSON using this worker's own
 * {@link ObjectMapper} (ISO-8601 timestamps, non-null inclusion), never
 * through a type-aware converter: no {@code __TypeId__} or other Java
 * class-name headers are stamped onto the wire. The consumer (control plane)
 * parses the JSON body into its own immutable DTO, so the worker's internal
 * domain types are never referenced across services.</p>
 */
@Component
public class PipelineResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(PipelineResultPublisher.class);
    private static final String HEADER_RETRY_COUNT = "x-retry-count";

    private final RabbitTemplate rabbitTemplate;
    private final WorkerProperties props;
    private final ObjectMapper objectMapper;

    public PipelineResultPublisher(RabbitTemplate rabbitTemplate, WorkerProperties props,
                                   ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public void publish(PipelineResult result) {
        String exchange = props.getRabbit().getResultsExchange();
        String routingKey = props.getRabbit().getResultRoutingKey();
        try {
            Message message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(result))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setHeader("jobId", result.jobId() == null ? "" : result.jobId())
                    .setHeader("pipelineId", result.pipelineId() == null ? "" : result.pipelineId())
                    .setHeader("status", result.status() == null ? "" : result.status().name())
                    .setHeader("workerId", result.workerId() == null ? "" : result.workerId())
                    .build();
            rabbitTemplate.send(exchange, routingKey, message);
            log.info("Published result for job {} with status {}", result.jobId(), result.status());
        } catch (Exception e) {
            log.error("Failed to publish result for job {}: {}", result.jobId(), safeMessage(e));
        }
    }

    public void publishRetry(PipelineJob job, int retryCount) {
        String exchange = props.getRabbit().getJobsExchange();
        String routingKey = props.getRabbit().getDelayRoutingKey();
        try {
            int next = retryCount + 1;
            Message message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(job))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setHeader(HEADER_RETRY_COUNT, next)
                    .setHeader("jobId", job.jobId())
                    .build();
            rabbitTemplate.send(exchange, routingKey, message);
            log.warn("Scheduled retry {} for job {} (delay {} ms)", next, job.jobId(), props.getRetryDelayMs());
        } catch (Exception e) {
            log.error("Failed to schedule retry for job {}: {}", job.jobId(), safeMessage(e));
        }
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
