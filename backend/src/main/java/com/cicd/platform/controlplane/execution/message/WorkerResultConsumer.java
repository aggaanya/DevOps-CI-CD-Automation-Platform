package com.cicd.platform.controlplane.execution.message;

import com.cicd.platform.controlplane.domain.entity.WorkerResult;
import com.cicd.platform.controlplane.domain.repository.WorkerResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Consumes structured {@code PipelineResult} messages published by standalone
 * workers on {@code cicd.results.exchange} (routing key {@code cicd.result})
 * and durably records them in {@code worker_results}.
 *
 * <p>ACK policy (manual): a message is acknowledged only after the result has
 * been persisted. Malformed bodies and persistence failures are rejected with
 * {@code requeue=false} so a defective message cannot loop forever. Duplicate
 * results for the same {@code jobId} are acknowledged and ignored (the worker
 * exchange is at-least-once).
 *
 * <p>The listener receives the raw {@link Message}: the worker serializes with
 * a {@code Jackson2JsonMessageConverter} (which stamps a {@code __TypeId__}
 * header referencing worker-internal classes that are not on this classpath),
 * so parsing is done here with the plain JSON body into
 * {@link WorkerResultMessage}.
 */
@Component
public class WorkerResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkerResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final WorkerResultRepository workerResultRepository;

    public WorkerResultConsumer(ObjectMapper objectMapper,
                                WorkerResultRepository workerResultRepository) {
        this.objectMapper = objectMapper;
        this.workerResultRepository = workerResultRepository;
    }

    @RabbitListener(
            queues = "${execution.results.queue}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void onWorkerResult(
            Message rawMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        String body = new String(rawMessage.getBody(), StandardCharsets.UTF_8);

        WorkerResultMessage result;
        try {
            result = objectMapper.readValue(body, WorkerResultMessage.class);
        } catch (Exception e) {
            log.warn("[WORKER_RESULT_REJECTED] unparseable result body (tag {}): {}", deliveryTag, safeBody(body));
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        if (result.jobId() == null || result.jobId().isBlank()) {
            log.warn("[WORKER_RESULT_REJECTED] result without jobId (tag {})", deliveryTag);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        if (workerResultRepository.existsByJobId(result.jobId())) {
            log.info("[WORKER_RESULT_DUPLICATE] jobId={} already recorded; acknowledging", result.jobId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            WorkerResult entity = new WorkerResult(
                    result.jobId(),
                    result.pipelineId(),
                    result.status() == null ? "UNKNOWN" : result.status(),
                    result.workerId(),
                    result.repositoryUrl(),
                    result.commitSha(),
                    result.branch(),
                    result.startedAt(),
                    result.completedAt(),
                    result.durationMs(),
                    result.message(),
                    body);
            workerResultRepository.save(entity);
            log.info("[WORKER_RESULT_RECORDED] jobId={}, status={}, workerId={}, durationMs={}",
                    result.jobId(), result.status(), result.workerId(), result.durationMs());
        } catch (Exception e) {
            log.error("[WORKER_RESULT_ERROR] failed to persist result for jobId={}: {}",
                    result.jobId(), safeMessage(e));
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        channel.basicAck(deliveryTag, false);
    }

    private String safeBody(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() > 512) {
            body = body.substring(0, 512) + "...";
        }
        return body.replaceAll("(?i)(password|token|secret|credential|authorization)\\s*[:=]\\s*\"?[^,\"}\\s]+",
                "$1=<redacted>");
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}