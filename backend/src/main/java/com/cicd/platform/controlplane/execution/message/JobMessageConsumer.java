package com.cicd.platform.controlplane.execution.message;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.execution.ExecutionContext;
import com.cicd.platform.controlplane.execution.ExecutionMdc;
import com.cicd.platform.controlplane.execution.PipelineOrchestrator;
import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import com.cicd.platform.controlplane.execution.worker.WorkerExecutor;
import com.cicd.platform.controlplane.execution.worker.WorkspaceManager;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JobMessageConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(JobMessageConsumer.class);

    private final PipelineOrchestrator orchestrator;
    private final WorkerExecutor workerExecutor;
    private final WorkspaceManager workspaceManager;
    private final WorkspaceConfig workspaceConfig;

    private final PipelineJobRepository pipelineJobRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final JobAttemptRepository jobAttemptRepository;

    public JobMessageConsumer(
            PipelineOrchestrator orchestrator,
            WorkerExecutor workerExecutor,
            WorkspaceManager workspaceManager,
            WorkspaceConfig workspaceConfig,
            PipelineJobRepository pipelineJobRepository,
            PipelineRunRepository pipelineRunRepository,
            JobAttemptRepository jobAttemptRepository) {

        this.orchestrator = orchestrator;
        this.workerExecutor = workerExecutor;
        this.workspaceManager = workspaceManager;
        this.workspaceConfig = workspaceConfig;
        this.pipelineJobRepository = pipelineJobRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.jobAttemptRepository = jobAttemptRepository;
    }

    @RabbitListener(
            queues = ExecutionConstants.JOB_DISPATCH_QUEUE,
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void onJobDispatch(
            JobDispatchMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        ExecutionMdc.setRunId(message.runId());
        ExecutionMdc.setJobId(message.jobId());
        log.info("[JOB_RECEIVED] jobId={}, jobName={}, runId={}, attemptNumber={}",
                message.jobId(), message.jobName(), message.runId(), message.attemptNumber());

        try {

            // ---------------------------------------------------------
            // 1. Find PipelineJob
            // ---------------------------------------------------------

            PipelineJob job = pipelineJobRepository
                    .findById(message.jobId())
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "PipelineJob not found: "
                                            + message.jobId()
                            )
                    );

            // ---------------------------------------------------------
            // 2. Find PipelineRun
            // ---------------------------------------------------------

            PipelineRun run = pipelineRunRepository
                    .findById(message.runId())
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "PipelineRun not found: "
                                            + message.runId()
                            )
                    );

            // ---------------------------------------------------------
            // 3. Check whether pipeline was cancelled
            // ---------------------------------------------------------

            if (run.getStatus() == PipelineRun.RunStatus.CANCELLED) {

                log.info("[JOB_SKIPPED] jobId={}, runId={}, reason=CANCELLED",
                        message.jobId(), message.runId());

                channel.basicAck(deliveryTag, false);
                return;
            }

            // ---------------------------------------------------------
            // 4. Prevent duplicate execution (atomic status transition)
            // ---------------------------------------------------------

            Instant runningAt = Instant.now();
            int claimed = pipelineJobRepository.transitionStatus(
                    message.jobId(),
                    PipelineJob.JobStatus.QUEUED,
                    PipelineJob.JobStatus.RUNNING,
                    workspaceConfig.getWorkerId(),
                    runningAt);

            if (claimed == 0) {

                log.info("[JOB_SKIPPED] jobId={}, status={}, reason=not-QUEUED",
                        message.jobId(), job.getStatus());

                channel.basicAck(deliveryTag, false);
                return;
            }

            job.setStatus(PipelineJob.JobStatus.RUNNING);
            job.setWorkerId(workspaceConfig.getWorkerId());
            job.setStartedAt(runningAt);

            // ---------------------------------------------------------
            // 5. Find current JobAttempt
            // ---------------------------------------------------------

            JobAttempt attempt = findCurrentAttempt(
                    message.jobId(),
                    message.attemptNumber()
            );

            if (attempt != null) {
                ExecutionMdc.setAttemptId(attempt.getId());

                attempt.setStatus(
                        JobAttempt.AttemptStatus.RUNNING
                );
                attempt.setStartedAt(Instant.now());

                jobAttemptRepository.save(attempt);
            }

            ExecutionMdc.setWorkerId(workspaceConfig.getWorkerId());

            // ---------------------------------------------------------
            // 6. Create workspace
            // ---------------------------------------------------------

            Path workspacePath =
                    workspaceManager.createWorkspace(
                            message.runId(),
                            message.jobId()
                    );

            Path workDir =
                    workspaceManager.getWorkDir(workspacePath);

            Path logsDir =
                    workspaceManager.getLogsDir(workspacePath);

            Path artifactsDir =
                    workspaceManager.getArtifactsDir(workspacePath);

            // ---------------------------------------------------------
            // 7. Build ExecutionContext
            // ---------------------------------------------------------

            ExecutionContext context = new ExecutionContext(
                    message.jobId(),
                    message.runId(),
                    message.jobName(),
                    PipelineJob.JobType.valueOf(
                            message.jobType()
                    ),
                    workspacePath,
                    workDir,
                    logsDir,
                    artifactsDir,
                    message.gitUrl(),
                    message.branch(),
                    message.commitSha(),
                    message.attemptNumber(),
                    workspaceConfig.getTimeoutSeconds(),
                    workspaceConfig.getWorkerId()
            );

            // ---------------------------------------------------------
            // 8. Execute job
            // ---------------------------------------------------------

            log.info("[JOB_STARTED] jobId={}, jobName={}, jobType={}, attemptNumber={}, workerId={}",
                    message.jobId(), message.jobName(), message.jobType(),
                    message.attemptNumber(), workspaceConfig.getWorkerId());

            boolean success =
                    workerExecutor.executeJob(context);

            int exitCode = success ? 0 : 1;

            // ---------------------------------------------------------
            // 9. Update JobAttempt
            // ---------------------------------------------------------

            if (attempt != null) {

                attempt.setStatus(
                        success
                                ? JobAttempt.AttemptStatus.SUCCESS
                                : JobAttempt.AttemptStatus.FAILED
                );

                attempt.setExitCode(exitCode);
                attempt.setFinishedAt(Instant.now());
                attempt.setLogsLocation(logsDir.toString());

                jobAttemptRepository.save(attempt);
            }

            // ---------------------------------------------------------
            // 10. Notify orchestrator
            // ---------------------------------------------------------

            orchestrator.handleJobCompletion(
                    message.jobId(),
                    success,
                    exitCode,
                    workspaceConfig.getWorkerId(),
                    job.getStartedAt(),
                    Instant.now()
            );

            // ---------------------------------------------------------
            // 11. Acknowledge RabbitMQ message
            // ---------------------------------------------------------

            channel.basicAck(deliveryTag, false);

            if (success) {
                log.info("[JOB_FINISHED] jobId={}, status=SUCCESS",
                        message.jobId());
            } else {
                log.warn("[JOB_FINISHED] jobId={}, status=FAILED, exitCode={}",
                        message.jobId(), exitCode);
            }

        } catch (Exception e) {

            log.error("[JOB_ERROR] jobId={}, runId={}, error={}",
                    message.jobId(), message.runId(), e.getMessage());

            try {

                JobAttempt failedAttempt = findCurrentAttempt(
                        message.jobId(),
                        message.attemptNumber()
                );

                if (failedAttempt != null
                        && failedAttempt.getStatus() != JobAttempt.AttemptStatus.SUCCESS
                        && failedAttempt.getStatus() != JobAttempt.AttemptStatus.FAILED
                        && failedAttempt.getStatus() != JobAttempt.AttemptStatus.CANCELLED) {

                    failedAttempt.setStatus(JobAttempt.AttemptStatus.FAILED);
                    failedAttempt.setExitCode(1);
                    failedAttempt.setFinishedAt(Instant.now());
                    jobAttemptRepository.save(failedAttempt);
                }

            } catch (Exception attemptEx) {

                log.error("[ATTEMPT_UPDATE_FAILED] jobId={}, error={}",
                        message.jobId(), attemptEx.getMessage());
            }

            try {

                channel.basicNack(
                        deliveryTag,
                        false,
                        false
                );

            } catch (IOException ioException) {

                log.error("[NACK_FAILED] jobId={}, error={}",
                        message.jobId(), ioException.getMessage());
            }
        } finally {
            ExecutionMdc.clearAll();
        }
    }

    private JobAttempt findCurrentAttempt(
            UUID jobId,
            int attemptNumber) {

        List<JobAttempt> attempts =
                jobAttemptRepository
                        .findByJobIdOrderByAttemptNumberAsc(jobId);

        return attempts.stream()
                .filter(attempt ->
                        attempt.getAttemptNumber() == attemptNumber)
                .findFirst()
                .orElse(null);
    }
}