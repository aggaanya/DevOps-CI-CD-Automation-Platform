package com.cicd.platform.worker.messaging;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.PipelineResult;
import com.cicd.platform.worker.domain.StageResult;
import com.cicd.platform.worker.exception.PipelineJobValidationException;
import com.cicd.platform.worker.exception.WorkerException;
import com.cicd.platform.worker.observability.ExecutionMetrics;
import com.cicd.platform.worker.service.DuplicateJobGuard;
import com.cicd.platform.worker.service.PipelineExecutionService;
import com.cicd.platform.worker.service.PipelineJobValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Consumes pipeline jobs from RabbitMQ and drives them through the execution
 * service.
 *
 * <p>ACK policy (manual): the message is acknowledged only after the job has
 * been fully handled \u2014 a result was published (for workload outcomes) or the
 * message was routed to retry/DLQ. Malformed or permanently invalid messages
 * are rejected into the dead-letter queue. Transient infrastructure failures
 * are retried through the delay queue up to {@code worker.max-retries}.</p>
 *
 * <p>RabbitMQ is at-least-once: {@link DuplicateJobGuard} prevents double
 * execution of the same {@code jobId} within this process.</p>
 *
 * <p>This consumer listens on two queues:
 * <ul>
 *   <li>{@code cicd.jobs} \u2014 legacy manual trigger format (loads pipeline.yml from repo)</li>
 *   <li>{@code pipeline-jobs} \u2014 control plane DAG dispatch format (uses declared steps from PipelineVersion)</li>
 * </ul>
 */
@Component
public class PipelineJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(PipelineJobConsumer.class);
    private static final String HEADER_RETRY_COUNT = "x-retry-count";

    private final ObjectMapper objectMapper;
    private final PipelineJobValidator jobValidator;
    private final PipelineExecutionService executionService;
    private final PipelineResultPublisher resultPublisher;
    private final DuplicateJobGuard duplicateJobGuard;
    private final ExecutionMetrics metrics;
    private final WorkerProperties props;

    public PipelineJobConsumer(@Qualifier("jsonObjectMapper") ObjectMapper objectMapper,
                               PipelineJobValidator jobValidator,
                               PipelineExecutionService executionService,
                               PipelineResultPublisher resultPublisher,
                               DuplicateJobGuard duplicateJobGuard,
                               ExecutionMetrics metrics, WorkerProperties props) {
        this.objectMapper = objectMapper;
        this.jobValidator = jobValidator;
        this.executionService = executionService;
        this.resultPublisher = resultPublisher;
        this.duplicateJobGuard = duplicateJobGuard;
        this.metrics = metrics;
        this.props = props;
    }

    /**
     * Legacy manual trigger queue \u2014 loads pipeline.yml from repository.
     */
    @RabbitListener(queues = "${worker.rabbit.job-queue}")
    public void onMessage(Message message, Channel channel) throws IOException {
        handleMessage(message, channel, "cicd.jobs");
    }

    /**
     * Control plane DAG dispatch queue \u2014 uses declared steps from PipelineVersion.
     */
    @RabbitListener(queues = "pipeline-jobs")
    public void onDispatchMessage(Message message, Channel channel) throws IOException {
        handleMessage(message, channel, "pipeline-jobs");
    }

    private void handleMessage(Message message, Channel channel, String queueName) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        PipelineJob job = null;
        try {
            job = objectMapper.readValue(body, PipelineJob.class);
            jobValidator.validate(job);
        } catch (JsonProcessingException e) {
            log.warn("Rejecting malformed job message from {} (tag {}): {}", queueName, deliveryTag, safeBody(body));
            channel.basicReject(deliveryTag, false);
            metrics.countMalformed();
            return;
        } catch (PipelineJobValidationException e) {
            log.warn("Rejecting invalid job from {} (tag {}): {}", queueName, deliveryTag, e.getMessage());
            publishFailure(jobIdOrUnknown(job));
            channel.basicReject(deliveryTag, false);
            metrics.countValidationFailure();
            return;
        }

        int attemptNumber = job.attemptNumber() != null ? job.attemptNumber() : 0;
        if (!duplicateJobGuard.tryAcquire(job.jobId(), attemptNumber)) {
            log.info("Duplicate job {} (attempt {}) detected; skipping execution", job.jobId(), attemptNumber);
            channel.basicAck(deliveryTag, false);
            return;
        }

        metrics.jobStarted();
        try {
            PipelineResult result = executionService.execute(job);
            duplicateJobGuard.markCompleted(job.jobId(), attemptNumber);
            metrics.jobFinished(result.status());
            resultPublisher.publish(result);
            channel.basicAck(deliveryTag, false);
            log.info("Acknowledged job {} from {} after {} ms with status {}",
                    job.jobId(), queueName, result.durationMs(), result.status());
        } catch (WorkerException e) {
            metrics.jobInfrastructureFailure();
            handleInfrastructureFailure(message, job, deliveryTag, channel, e, attemptNumber);
        } catch (Exception e) {
            metrics.jobInfrastructureFailure();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Unexpected failure handling job {}: {}", job.jobId(), msg, e);
            handleInfrastructureFailure(message, job, deliveryTag, channel,
                    new WorkerException("Unexpected failure: " + msg, e), attemptNumber);
        }
    }

    private void handleInfrastructureFailure(Message message, PipelineJob job, long deliveryTag,
                                             Channel channel, WorkerException failure,
                                             int attemptNumber) throws IOException {
        int retryCount = retryCount(message);
        boolean canRetry = props.isRetryEnabled() && retryCount < props.getMaxRetries();
        if (canRetry) {
            duplicateJobGuard.markFailed(job.jobId(), attemptNumber);
            resultPublisher.publishRetry(job, retryCount);
            channel.basicAck(deliveryTag, false);
            log.warn("Retried job {} (attempt {}) after infrastructure failure: {}",
                    job.jobId(), attemptNumber, failure.getMessage());
        } else {
            duplicateJobGuard.markCompleted(job.jobId(), attemptNumber);
            publishFailure(job, "Infrastructure failure: " + failure.getMessage());
            channel.basicReject(deliveryTag, false);
            log.error("Permanently failed job {} (retries exhausted): {}", job.jobId(), failure.getMessage());
        }
    }

    private int retryCount(Message message) {
        Object header = message.getMessageProperties().getHeader(HEADER_RETRY_COUNT);
        if (header instanceof Number number) {
            return number.intValue();
        }
        if (header instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }

    private void publishFailure(PipelineJob job, String reason) {
        Instant now = Instant.now();
        PipelineResult result = new PipelineResult(job.jobId(), job.pipelineId(), JobStatus.FAILED,
                props.getId(), redactUrl(job.repositoryUrl()), job.commitSha(), job.branch(),
                now, now, 0L, List.<StageResult>of(), reason);
        resultPublisher.publish(result);
    }

    private void publishFailure(String jobId) {
        if (jobId == null) {
            return;
        }
        Instant now = Instant.now();
        PipelineResult result = new PipelineResult(jobId, null, JobStatus.FAILED,
                props.getId(), null, null, null, now, now, 0L, List.of(),
                "Job message failed validation");
        resultPublisher.publish(result);
    }

    private String jobIdOrUnknown(PipelineJob job) {
        return job == null || job.jobId() == null ? "unknown" : job.jobId();
    }

    private String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("(https?://)([^@/]+)@", "$1<redacted>@");
    }

    private String safeBody(String body) {
        if (body.length() > 512) {
            body = body.substring(0, 512) + "...";
        }
        return body.replaceAll("(?i)(password|token|secret|credential|authorization)\\s*[:=]\\s*\"?[^,\"}\\s]+",
                "$1=<redacted>");
    }
}
