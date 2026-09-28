package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StepDefinition;
import com.cicd.platform.controlplane.pipeline.dag.DagReadiness;
import com.cicd.platform.controlplane.pipeline.dag.PipelineDag;
import com.cicd.platform.controlplane.pipeline.dag.PipelineDagRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides which jobs of a run may start, publishes them to RabbitMQ, and marks
 * jobs unreachable because of a failure as SKIPPED.
 *
 * <h2>Scheduling</h2>
 * The run's resolved {@link PipelineDag} is the single source of truth for
 * ordering. A job becomes runnable when every DAG dependency has succeeded; two
 * jobs with no dependency on each other are therefore runnable at the same
 * instant, which is what makes independent branches execute concurrently rather
 * than being walked one after another.
 *
 * <h2>Duplicate-execution safety</h2>
 * Publishing is gated on a conditional UPDATE ({@code claimForDispatch}) that only
 * one caller can win per job. That makes the dispatcher safe under concurrent
 * scheduler passes — several job-completion callbacks plus the trigger request —
 * and across multiple control-plane instances, without holding a lock for the
 * lifetime of a job.
 *
 * <h2>Failure propagation</h2>
 * A job whose dependency is terminal-and-unsuccessful is moved to the terminal
 * {@code SKIPPED} state instead of being left PENDING forever, and the blocking
 * dependency is recorded in the outbox payload. Stage and run states are then
 * settled by {@link RunStateSettler}, which guarantees the run reaches a terminal
 * FAILED state even when the last thing to happen was a skip rather than a
 * completion.
 */
@Service
public class JobDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcherService.class);

    private final RabbitTemplate rabbitTemplate;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final PipelineJobRepository pipelineJobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final RunSchedulerLockRegistry runLocks;
    private final RunStateSettler runStateSettler;
    private final OutboxEventService outboxEventService;
    private final PipelineDagRegistry dagRegistry = new PipelineDagRegistry();

    public JobDispatcherService(RabbitTemplate rabbitTemplate,
                                PipelineRunRepository pipelineRunRepository,
                                PipelineStageRepository pipelineStageRepository,
                                PipelineJobRepository pipelineJobRepository,
                                JobAttemptRepository jobAttemptRepository,
                                RunSchedulerLockRegistry runLocks,
                                RunStateSettler runStateSettler,
                                OutboxEventService outboxEventService) {
        this.rabbitTemplate = rabbitTemplate;
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineStageRepository = pipelineStageRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.runLocks = runLocks;
        this.runStateSettler = runStateSettler;
        this.outboxEventService = outboxEventService;
    }

    /**
     * One scheduling pass for a run: skip what can never run, claim what can run
     * now, then settle any stage/run that this pass made terminal.
     *
     * <p>Idempotent and safe to call from many threads at once. A pass that cannot
     * take the run lock gives up rather than blocking the caller indefinitely; the
     * next state change triggers another pass.
     */
    public void dispatchReadyJobs(UUID runId) {
        if (runId == null) {
            return;
        }
        runLocks.withRunLock(runId, () -> {
            dispatchReadyJobsLocked(runId);
            return null;
        });
    }

    /**
     * Runs a scheduling pass in a brand-new transaction, suspending any current
     * one.
     *
     * <p>This is the entry point to use from a post-commit callback. The {@code
     * afterCommit} step of a completed transaction still has that transaction
     * bound to the thread, so REQUIRED propagation would make every repository
     * call join an already-committed transaction and fail with {@code no
     * transaction is in progress}. REQUIRES_NEW suspends it and opens a context
     * that can see the freshly committed run rows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchReadyJobsInNewTransaction(UUID runId) {
        dispatchReadyJobs(runId);
    }

    @Transactional
    public void dispatchReadyJobsLocked(UUID runId) {
        PipelineRun run = pipelineRunRepository.findById(runId).orElse(null);
        if (run == null || run.getStatus() != PipelineRun.RunStatus.RUNNING) {
            return;
        }

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(runId);
        if (stages.isEmpty()) {
            return;
        }

        Map<UUID, List<PipelineJob>> jobsByStage = new LinkedHashMap<>();
        Map<UUID, PipelineJob> allJobsById = new LinkedHashMap<>();
        List<PipelineJob> allJobs = new ArrayList<>();
        for (PipelineStage stage : stages) {
            List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stage.getId());
            jobsByStage.put(stage.getId(), jobs);
            for (PipelineJob job : jobs) {
                allJobsById.put(job.getId(), job);
            }
            allJobs.addAll(jobs);
        }

        PipelineVersion version = run.getPipelineVersion();
        PipelineDag dag = dagRegistry.resolveForRun(version, stages, jobsByStage);
        if (dag == null) {
            // No parseable graph: keep the historical behaviour of walking stages
            // in written order rather than refusing to schedule the run.
            dispatchAllPendingWithoutDag(run, stages, jobsByStage, allJobsById, allJobs);
            return;
        }

        DagReadiness.Plan plan = DagReadiness.evaluate(dag, allJobs);
        if (!plan.unmappedJobIds().isEmpty()) {
            log.warn("[DISPATCH_UNMAPPED] runId={}, jobCount={} — no declared DAG for these jobs",
                    runId, plan.unmappedJobIds().size());
        }

        int skipped = skipBlockedJobs(runId, plan);
        int dispatched = claimAndDispatch(run, allJobsById, plan.runnableJobIds());

        if (skipped > 0 || dispatched > 0) {
            log.info("[DISPATCH_READY] runId={}, dispatched={}, skipped={}, waiting={}, totalJobs={}",
                    runId, dispatched, skipped, plan.waitingJobIds().size(), allJobs.size());
        }

        if (skipped > 0) {
            settleIfTerminal(runId);
        }
    }

    /**
     * Fallback for runs whose YAML cannot be resolved into a graph: dispatch every
     * PENDING job whose preceding stages have all succeeded, ignoring job-level
     * ordering. Preserves pre-DAG behaviour exactly.
     */
    private void dispatchAllPendingWithoutDag(PipelineRun run,
                                              List<PipelineStage> stages,
                                              Map<UUID, List<PipelineJob>> jobsByStage,
                                              Map<UUID, PipelineJob> allJobsById,
                                              List<PipelineJob> allJobs) {
        List<UUID> candidates = new ArrayList<>();
        for (PipelineStage stage : stages) {
            if (stage.getStatus() == PipelineStage.StageStatus.FAILED
                    || stage.getStatus() == PipelineStage.StageStatus.SKIPPED
                    || stage.getStatus() == PipelineStage.StageStatus.SUCCESS) {
                continue;
            }
            if (!allPreviousStagesSucceeded(stage, stages)) {
                continue;
            }
            for (PipelineJob job : jobsByStage.getOrDefault(stage.getId(), List.of())) {
                if (job.getStatus() == PipelineJob.JobStatus.PENDING) {
                    candidates.add(job.getId());
                }
            }
        }
        int dispatched = claimAndDispatch(run, allJobsById, candidates);
        if (dispatched > 0) {
            log.info("[DISPATCH_POSITIONAL] runId={}, dispatched={}, totalJobs={}",
                    run.getId(), dispatched, allJobs.size());
        }
    }

    private boolean allPreviousStagesSucceeded(PipelineStage stage, List<PipelineStage> stages) {
        for (PipelineStage previous : stages) {
            if (previous.getOrderIndex() >= stage.getOrderIndex()) {
                break;
            }
            if (previous.getStatus() != PipelineStage.StageStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    private int skipBlockedJobs(UUID runId, DagReadiness.Plan plan) {
        int skipped = 0;
        Instant now = Instant.now();
        for (DagReadiness.BlockedJob blocked : plan.blockedJobs()) {
            int updated = pipelineJobRepository.markTerminal(blocked.jobId(),
                    PipelineJob.JobStatus.PENDING, PipelineJob.JobStatus.SKIPPED, now);
            if (updated == 1) {
                skipped++;
                log.info("[JOB_SKIPPED] jobId={}, jobName={}, stage={}, runId={}, blockedBy={}",
                        blocked.jobId(), blocked.jobName(), blocked.stageName(), runId,
                        blocked.blockedBy());
                outboxPublishSkip(runId, blocked);
            }
        }
        return skipped;
    }

    private void outboxPublishSkip(UUID runId, DagReadiness.BlockedJob blocked) {
        // Routed through the run's own event stream so a dashboard or an operator
        // can tell "never ran because upstream failed" from "ran and failed".
        try {
            outboxEventService.publishEvent("JOB_SKIPPED", "PipelineJob", blocked.jobId(),
                    Map.of("runId", runId, "jobName", String.valueOf(blocked.jobName()),
                            "blockedBy", String.valueOf(blocked.blockedBy())));
        } catch (RuntimeException e) {
            log.warn("Failed to publish JOB_SKIPPED for jobId={}: {}", blocked.jobId(), e.getMessage());
        }
    }

    /**
     * Atomically claims each candidate and publishes a dispatch message.
     *
     * @return the number of jobs this caller actually claimed
     */
    private int claimAndDispatch(PipelineRun run, Map<UUID, PipelineJob> jobsById,
                                 List<UUID> candidateJobIds) {
        int dispatched = 0;
        for (UUID jobId : candidateJobIds) {
            PipelineJob job = jobsById.get(jobId);
            if (job == null) {
                continue;
            }
            try {
                if (dispatchClaimedJob(run, job, List.of(PipelineJob.JobStatus.PENDING))) {
                    dispatched++;
                }
            } catch (RuntimeException e) {
                log.error("[JOB_DISPATCH_FAILED] jobId={}, runId={}, error={}",
                        jobId, run.getId(), e.getMessage(), e);
            }
        }
        return dispatched;
    }

    /**
     * The scalar values a dispatch message needs, captured while the persistence
     * context is still open.
     *
     * <p>{@code claimForDispatch} is declared {@code clearAutomatically = true} so
     * that the post-claim re-read observes the new status. That also detaches
     * everything the scheduler loaded before the claim, including the run's lazy
     * {@code Repository} and the version's YAML. Reading those afterwards throws
     * {@code LazyInitializationException} and the job would be claimed but never
     * published. Resolving the message contents first, then claiming, then
     * publishing, keeps exactly one code path and avoids depending on session
     * state.
     */
    private record DispatchTarget(UUID runId, String gitUrl, String branch, String commitSha,
                                  UUID versionId, List<StepDefinition> steps) {}

    /**
     * Builds a dispatch target, resolving the declared steps and the repository
     * coordinates up front.
     */
    private DispatchTarget resolveTarget(PipelineRun run, PipelineJob job) {
        PipelineVersion version = run.getPipelineVersion();
        String gitUrl = run.getRepository() == null ? "" : run.getRepository().getRepositoryUrl();
        String stageName = job.getPipelineStage() == null ? null : job.getPipelineStage().getName();
        List<StepDefinition> steps = version == null
                ? List.of()
                : dagRegistry.stepsFor(version, stageName, job.getName());
        return new DispatchTarget(
                run.getId(),
                gitUrl,
                run.getBranch(),
                run.getCommitSha(),
                version == null ? null : version.getId(),
                steps);
    }

    /**
     * Claims one job, opens an attempt, and publishes the message.
     *
     * @param job               the job as the scheduler loaded it, still managed
     * @param expectedStatuses  the statuses this caller is allowed to claim from
     * @return true when this caller won the claim
     */
    private boolean dispatchClaimedJob(PipelineRun run, PipelineJob job,
                                       List<PipelineJob.JobStatus> expectedStatuses) {
        UUID jobId = job.getId();
        DispatchTarget target = resolveTarget(run, job);

        int claimed = pipelineJobRepository.claimForDispatch(
                jobId, expectedStatuses, PipelineJob.JobStatus.QUEUED);
        if (claimed == 0) {
            log.info("[JOB_CLAIM_LOST] jobId={}, runId={} — another scheduler already dispatched it",
                    jobId, target.runId());
            return false;
        }

        // The claim UPDATE clears the persistence context, so re-read the job to
        // work from its post-claim state rather than a stale in-memory copy.
        PipelineJob claimedJob = pipelineJobRepository.findById(jobId).orElse(null);
        if (claimedJob == null) {
            log.warn("[JOB_CLAIM_ORPHAN] jobId={}, runId={} — job vanished after claim", jobId, target.runId());
            return false;
        }

        int attemptNumber = computeAttemptNumber(claimedJob);
        JobAttempt attempt = new JobAttempt(claimedJob, attemptNumber);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);
        jobAttemptRepository.save(attempt);

        ExecutionMdc.setRunId(target.runId());
        ExecutionMdc.setStageId(claimedJob.getPipelineStage() == null
                ? null : claimedJob.getPipelineStage().getId());
        ExecutionMdc.setJobId(claimedJob.getId());
        ExecutionMdc.setAttemptId(attempt.getId());
        try {
            publishDispatchMessage(buildDispatchMessage(claimedJob, target, attemptNumber));
            log.info("[JOB_DISPATCHED] jobId={}, jobName={}, jobType={}, attemptNumber={}, stageId={}, runId={}",
                    claimedJob.getId(), claimedJob.getName(), claimedJob.getJobType(), attemptNumber,
                    claimedJob.getPipelineStage() == null ? null : claimedJob.getPipelineStage().getId(),
                    target.runId());
        } finally {
            ExecutionMdc.clearAll();
        }
        return true;
    }

    /**
     * Claims a job on behalf of an external caller (currently only the retry path)
     * and publishes a new attempt.
     *
     * @return true when the claim was won
     */
    public boolean dispatchJob(PipelineJob job) {
        if (job == null || job.getPipelineStage() == null || job.getPipelineStage().getPipelineRun() == null) {
            return false;
        }
        PipelineRun run = job.getPipelineStage().getPipelineRun();
        return dispatchClaimedJob(run, job, List.of(PipelineJob.JobStatus.PENDING));
    }

    /**
     * Re-queues a failed job as a new attempt.
     *
     * <p>Retries claim from {@code FAILED}/{@code CANCELLED}/{@code PENDING} rather
     * than from {@code PENDING} only, because a job that has already been attempted
     * is no longer PENDING when its retry is scheduled.
     */
    @Transactional
    public void dispatchForRetry(PipelineJob job, int attemptNumber) {
        if (job == null || job.getPipelineStage() == null || job.getPipelineStage().getPipelineRun() == null) {
            return;
        }
        PipelineStage stage = job.getPipelineStage();
        PipelineRun run = stage.getPipelineRun();
        UUID jobId = job.getId();

        // Resolved before the claim: the claim clears the persistence context, and
        // the run's repository / version YAML are lazy.
        DispatchTarget target = resolveTarget(run, job);

        int claimed = pipelineJobRepository.claimForDispatch(jobId,
                List.of(PipelineJob.JobStatus.FAILED, PipelineJob.JobStatus.CANCELLED,
                        PipelineJob.JobStatus.PENDING),
                PipelineJob.JobStatus.QUEUED);
        if (claimed == 0) {
            log.info("[JOB_RETRY_CLAIM_LOST] jobId={}, runId={}, attempt={}", jobId, target.runId(), attemptNumber);
            return;
        }

        PipelineJob claimedJob = pipelineJobRepository.findById(jobId).orElse(null);
        if (claimedJob == null) {
            return;
        }

        JobAttempt attempt = new JobAttempt(claimedJob, attemptNumber);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);
        jobAttemptRepository.save(attempt);

        ExecutionMdc.setRunId(target.runId());
        ExecutionMdc.setStageId(stage.getId());
        ExecutionMdc.setJobId(jobId);
        ExecutionMdc.setAttemptId(attempt.getId());
        try {
            publishDispatchMessage(buildDispatchMessage(claimedJob, target, attemptNumber));
            log.info("[JOB_RETRY_DISPATCHED] jobId={}, jobName={}, attemptNumber={}, stageId={}, runId={}",
                    jobId, claimedJob.getName(), attemptNumber, stage.getId(), target.runId());
        } finally {
            ExecutionMdc.clearAll();
        }
    }

    private void settleIfTerminal(UUID runId) {
        runStateSettler.settleRun(runId);
    }

    /**
     * Re-claims a job that is QUEUED but whose dispatch message was lost.
     *
     * <p>Publishing inside the transaction that creates the run's rows lets a fast
     * consumer read the job before commit and dead-letter the message, leaving the
     * job QUEUED with no worker ever seeing it. {@link
     * StaleDispatchRecoveryService} finds those orphans and this method puts a
     * fresh attempt and message back out for the job. Claiming QUEUED-to-QUEUED
     * under the run lock serialises the recovery against a normal scheduler pass,
     * so a concurrent dispatcher cannot double-publish.
     *
     * @return true when this caller re-claimed and re-published the job
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverStaleDispatch(UUID runId, UUID jobId) {
        PipelineJob job = pipelineJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getPipelineStage() == null
                || job.getPipelineStage().getPipelineRun() == null) {
            return false;
        }
        PipelineStage stage = job.getPipelineStage();
        PipelineRun run = stage.getPipelineRun();
        if (!runId.equals(run.getId()) || run.getStatus() != PipelineRun.RunStatus.RUNNING) {
            return false;
        }
        DispatchTarget target = resolveTarget(run, job);

        int claimed = pipelineJobRepository.claimForDispatch(jobId,
                List.of(PipelineJob.JobStatus.QUEUED),
                PipelineJob.JobStatus.QUEUED);
        if (claimed == 0) {
            log.info("[JOB_RECOVERY_CLAIM_LOST] jobId={}, runId={}", jobId, target.runId());
            return false;
        }

        PipelineJob claimedJob = pipelineJobRepository.findById(jobId).orElse(null);
        if (claimedJob == null) {
            return false;
        }

        int attemptNumber = computeAttemptNumber(claimedJob);
        JobAttempt attempt = new JobAttempt(claimedJob, attemptNumber);
        attempt.setStatus(JobAttempt.AttemptStatus.PENDING);
        jobAttemptRepository.save(attempt);

        ExecutionMdc.setRunId(target.runId());
        ExecutionMdc.setStageId(stage.getId());
        ExecutionMdc.setJobId(jobId);
        ExecutionMdc.setAttemptId(attempt.getId());
        try {
            publishDispatchMessage(buildDispatchMessage(claimedJob, target, attemptNumber));
            log.info("[JOB_RECOVERED] jobId={}, jobName={}, attemptNumber={}, runId={}",
                    jobId, claimedJob.getName(), attemptNumber, target.runId());
        } finally {
            ExecutionMdc.clearAll();
        }
        return true;
    }

    private int computeAttemptNumber(PipelineJob job) {
        List<JobAttempt> previousAttempts =
                jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
        if (previousAttempts.isEmpty()) {
            return 1;
        }
        return previousAttempts.get(previousAttempts.size() - 1).getAttemptNumber() + 1;
    }

    private JobDispatchMessage buildDispatchMessage(PipelineJob job, DispatchTarget target,
                                                    int attemptNumber) {
        return JobDispatchMessage.create(
                job.getId(),
                target.runId(),
                target.versionId(),
                job.getName(),
                job.getJobType().name(),
                target.gitUrl(),
                target.branch(),
                target.commitSha(),
                attemptNumber,
                target.steps());
    }

    private void sendDispatchMessage(JobDispatchMessage message) {
        rabbitTemplate.convertAndSend(
                ExecutionConstants.JOB_DISPATCH_EXCHANGE,
                ExecutionConstants.JOB_DISPATCH_ROUTING_KEY,
                message);
    }

    /**
     * Publishes a dispatch message. When the claim and attempt rows belong to an
     * open transaction, the publish is deferred until that transaction commits.
     *
     * <p>Messages must never be sent from inside the transaction that wrote the
     * claim: a fast consumer could receive the message, re-load the job, observe
     * it still {@code PENDING} (because the {@code claimForDispatch} UPDATE is not
     * committed yet), conclude the claim was lost, and drop the message — leaving
     * the job QUEUED with no worker ever assigned (the original pre-commit race,
     * one level down). Publishing after the commit makes the ordering
     * deterministic: by the time anyone can see the message, the job is QUEUED
     * and its attempt row is visible.
     *
     * <p>When there is no surrounding transaction, each repository operation
     * commits on return, so the claim and attempt are already durable before this
     * method is reached and publishing immediately is safe.
     */
    private void publishDispatchMessage(JobDispatchMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        sendDispatchMessage(message);
                    } catch (RuntimeException e) {
                        // RabbitMQ unreachable: the job stays QUEUED and the stale
                        // dispatch sweep republishes it once the broker recovers.
                        log.error("[DISPATCH_PUBLISH_FAILED] error={}, message={}",
                                e.getMessage(), message, e);
                    }
                }
            });
        } else {
            sendDispatchMessage(message);
        }
    }
}
