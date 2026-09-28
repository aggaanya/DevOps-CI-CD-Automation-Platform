package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Finds jobs that were queued for dispatch but whose RabbitMQ message was lost,
 * and republishes them.
 *
 * <p>A dispatch message is written inside a database transaction. If that message
 * reaches the consumer before the {@code pipeline_jobs} rows commit, the consumer
 * cannot find the job, the message is dead-lettered, and the job sits QUEUED with
 * nothing ever executing it. The primary fix is dispatching only after commit;
 * this sweep is the safety net for every other way a message can fail to reach a
 * worker (broker hiccup, manual reject, lost ack) and for runs created before the
 * primary fix was deployed.
 *
 * <p>A job is considered stuck when it is QUEUED, its run is still RUNNING, and it
 * has no attempt that could be in flight (a PENDING or RUNNING attempt row).
 * Re-dispatch claims the job back under the run lock, so it cannot race a normal
 * scheduler pass.
 */
@Service
public class StaleDispatchRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(StaleDispatchRecoveryService.class);

    private final PipelineJobRepository pipelineJobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final JobDispatcherService jobDispatcherService;
    private final RunSchedulerLockRegistry runLocks;
    private final long pollIntervalMillis;

    public StaleDispatchRecoveryService(
            PipelineJobRepository pipelineJobRepository,
            JobAttemptRepository jobAttemptRepository,
            JobDispatcherService jobDispatcherService,
            RunSchedulerLockRegistry runLocks,
            @Value("${app.stale-dispatch-poll-ms:15000}") long pollIntervalMillis) {
        this.pipelineJobRepository = pipelineJobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobDispatcherService = jobDispatcherService;
        this.runLocks = runLocks;
        this.pollIntervalMillis = pollIntervalMillis;
    }

    @Scheduled(fixedDelayString = "${app.stale-dispatch-poll-ms:15000}")
    public void recoverStaleDispatches() {
        if (pollIntervalMillis <= 0) {
            return;
        }
        for (PipelineJob job : pipelineJobRepository.findByStatusWithRun(PipelineJob.JobStatus.QUEUED)) {
            PipelineStage stage = job.getPipelineStage();
            PipelineRun run = stage == null ? null : stage.getPipelineRun();
            if (run == null || run.getStatus() != PipelineRun.RunStatus.RUNNING) {
                continue;
            }
            if (dispatchStillInFlight(job)) {
                continue;
            }
            log.info("[STALE_DISPATCH] jobId={}, jobName={}, runId={} — re-dispatching lost message",
                    job.getId(), job.getName(), run.getId());
            runLocks.withRunLock(run.getId(),
                    () -> jobDispatcherService.recoverStaleDispatch(run.getId(), job.getId()));
        }
    }

    private boolean dispatchStillInFlight(PipelineJob job) {
        List<JobAttempt> attempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
        for (JobAttempt attempt : attempts) {
            if (attempt.getStatus() == JobAttempt.AttemptStatus.PENDING
                    || attempt.getStatus() == JobAttempt.AttemptStatus.RUNNING) {
                return true;
            }
        }
        return false;
    }
}