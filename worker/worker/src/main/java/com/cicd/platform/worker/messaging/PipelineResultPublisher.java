package com.cicd.platform.worker.messaging;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.PipelineResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes structured {@link PipelineResult} objects to the results exchange
 * and re-publishes jobs that need a retry (with backoff through the delay
 * queue).
 */
@Component
public class PipelineResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(PipelineResultPublisher.class);
    private static final String HEADER_RETRY_COUNT = "x-retry-count";

    private final RabbitTemplate rabbitTemplate;
    private final WorkerProperties props;

    public PipelineResultPublisher(RabbitTemplate rabbitTemplate, WorkerProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
    }

    public void publish(PipelineResult result) {
        String exchange = props.getRabbit().getResultsExchange();
        String routingKey = props.getRabbit().getResultRoutingKey();
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, result,
                    message -> {
                        message.getMessageProperties().setHeader("jobId", result.jobId());
                        message.getMessageProperties().setHeader("pipelineId", result.pipelineId());
                        message.getMessageProperties().setHeader("status", result.status().name());
                        message.getMessageProperties().setHeader("workerId", result.workerId());
                        return message;
                    });
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
            rabbitTemplate.convertAndSend(exchange, routingKey, job,
                    message -> {
                        message.getMessageProperties().setHeader(HEADER_RETRY_COUNT, next);
                        message.getMessageProperties().setHeader("jobId", job.jobId());
                        return message;
                    });
            log.warn("Scheduled retry {} for job {} (delay {} ms)", next, job.jobId(), props.getRetryDelayMs());
        } catch (Exception e) {
            log.error("Failed to schedule retry for job {}: {}", job.jobId(), safeMessage(e));
        }
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
