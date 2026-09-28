package com.cicd.platform.controlplane.execution.message;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.WorkerResult;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.WorkerResultRepository;
import com.cicd.platform.controlplane.execution.PipelineOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
 * <p>The listener receives the raw {@link Message}: the worker publishes the
 * result as plain JSON (no {@code __TypeId__} or other Java class-name
 * headers), and parsing is done here with the plain JSON body into the
 * control plane's own immutable {@link WorkerResultMessage}.
 */
@Component
public class WorkerResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkerResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final WorkerResultRepository workerResultRepository;
    private final PipelineJobRepository pipelineJobRepository;
    private final PipelineOrchestrator orchestrator;

    public WorkerResultConsumer(ObjectMapper objectMapper,
                                WorkerResultRepository workerResultRepository,
                                PipelineJobRepository pipelineJobRepository,
                                PipelineOrchestrator orchestrator) {
        this.objectMapper = objectMapper;
        this.workerResultRepository = workerResultRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.orchestrator = orchestrator;
    }

    @RabbitListener(
            queues = "${execution.results.queue}",
            containerFactory = "controlPlaneListenerContainerFactory"
    )
    @Transactional
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

        if (workerResultRepository.existsByJobIdAndStatusAndDurationMs(
                result.jobId(), result.status(), result.durationMs())) {
            log.info("[WORKER_RESULT_DUPLICATE] jobId={}, status={}, durationMs={} already recorded; acknowledging",
                    result.jobId(), result.status(), result.durationMs());
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

        advanceRunState(result);

        channel.basicAck(deliveryTag, false);
    }

    /**
     * Advances the pipeline run state when the finished job belongs to a run.
     *
     * <p>The standalone worker executes a job and publishes only a {@code
     * PipelineResult} (job id, status, timestamps) — it has no knowledge of the
     * run. The control plane owns the run graph, so the result is correlated back
     * to its {@link PipelineJob} row and handed to the orchestrator, which is the
     * single writer of job/stage/run state. Jobs that do not belong to a run
     * (legacy manual triggers) are recorded but never advance a run.</p>
     *
     * <p>Idempotent: {@link PipelineOrchestrator#handleJobCompletion} ignores a
     * job that is already terminal, so a redelivered result cannot double-apply.</p>
     */
    private void advanceRunState(WorkerResultMessage result) {
        final UUID jobId;
        try {
            jobId = UUID.fromString(result.jobId());
        } catch (IllegalArgumentException e) {
            // Legacy manual-trigger jobs use string ids that are not UUIDs and do
            // not belong to a run; the result is recorded but no run is advanced.
            return;
        }
        pipelineJobRepository.findById(jobId).ifPresent(job -> {
            PipelineStage stage = job.getPipelineStage();
            PipelineRun run = stage == null ? null : stage.getPipelineRun();
            if (run == null) {
                return;
            }
            boolean success = "SUCCESS".equals(result.status());
            orchestrator.handleJobCompletion(jobId, success, success ? 0 : 1,
                    result.workerId(), result.startedAt(), result.completedAt());
            log.info("[WORKER_RESULT_ORCHESTRATED] jobId={}, runId={}, status={}",
                    jobId, run.getId(), result.status());
        });
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