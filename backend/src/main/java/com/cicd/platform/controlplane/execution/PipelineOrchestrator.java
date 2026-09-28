package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.JobDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StageDefinition;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.dag.DependencyNames;
import com.cicd.platform.controlplane.pipeline.parser.PipelineYamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the lifecycle of a run: materialises its stages and jobs, records worker
 * results, and drives cancellation.
 *
 * <p><b>Concurrency.</b> Once a run has independent branches, several worker
 * threads can report results for the same run at the same time. Every method here
 * therefore runs under the run's scheduler lock, and every state change it makes
 * is a conditional UPDATE. The lock orders the decisions; the database guarantees
 * them. Job execution itself happens in the consumer, outside the lock, which is
 * what keeps parallel branches genuinely parallel.
 */
@Service
@Transactional
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final PipelineJobRepository pipelineJobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final PipelineYamlParser pipelineYamlParser = new PipelineYamlParser();
    private final PipelineConfigMapper pipelineConfigMapper = new PipelineConfigMapper();
    private final JobDispatcherService jobDispatcherService;
    private final RunStateSettler runStateSettler;
    private final RunSchedulerLockRegistry runLocks;
    private final WorkspaceConfig workspaceConfig;
    private final OutboxEventService outboxEventService;

    public PipelineOrchestrator(
            PipelineRunRepository pipelineRunRepository,
            PipelineStageRepository pipelineStageRepository,
            PipelineJobRepository pipelineJobRepository,
            JobAttemptRepository jobAttemptRepository,
            JobDispatcherService jobDispatcherService,
            RunStateSettler runStateSettler,
            RunSchedulerLockRegistry runLocks,
            WorkspaceConfig workspaceConfig,
            OutboxEventService outboxEventService) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineStageRepository = pipelineStageRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobDispatcherService = jobDispatcherService;
        this.runStateSettler = runStateSettler;
        this.runLocks = runLocks;
        this.workspaceConfig = workspaceConfig;
        this.outboxEventService = outboxEventService;
    }

    /**
     * Creates the run's stage and job rows, marks the run RUNNING and schedules the
     * first dispatch pass.
     *
     * <p>The declared dependencies are persisted on the rows themselves
     * ({@code pipeline_stages.depends_on} / {@code pipeline_jobs.depends_on}) so
     * the graph a run executes is an immutable property of that run and can be
     * replayed and audited later without re-parsing YAML.
     */
    public PipelineRun startExecution(PipelineRun run) {
        ExecutionMdc.setRunId(run.getId());
        try {
            log.info("[RUN_STARTED] runId={}, status=RUNNING", run.getId());

            PipelineVersion version = run.getPipelineVersion();
            if (version == null) {
                throw new BusinessRuleException("Pipeline run must have an associated pipeline version");
            }

            PipelineConfig config = pipelineYamlParser.parse(version.getYamlContent());
            List<StageDefinition> stageDefinitions = pipelineConfigMapper.toStageDefinitions(config);

            for (StageDefinition stageDef : stageDefinitions) {
                PipelineStage stage = new PipelineStage();
                stage.setName(stageDef.name());
                stage.setOrderIndex(stageDef.orderIndex());
                stage.setStatus(PipelineStage.StageStatus.PENDING);
                stage.setDependencies(stageDef.dependsOn());
                stage.setPipelineRun(run);
                stage = pipelineStageRepository.save(stage);
                log.info("[STAGE_CREATED] stageId={}, stageName={}, orderIndex={}, dependsOn={}, status=PENDING",
                        stage.getId(), stage.getName(), stage.getOrderIndex(),
                        DependencyNames.encode(stageDef.dependsOn()));

                for (JobDefinition jobDef : stageDef.jobs()) {
                    PipelineJob job = new PipelineJob();
                    job.setName(jobDef.name());
                    job.setJobType(jobDef.jobType());
                    job.setStatus(PipelineJob.JobStatus.PENDING);
                    job.setDependencies(jobDef.dependsOn());
                    job.setPipelineStage(stage);
                    pipelineJobRepository.save(job);
                }
            }

            run.setStatus(PipelineRun.RunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            PipelineRun savedRun = pipelineRunRepository.save(run);

            outboxEventService.publishEvent("RUN_STARTED", "PipelineRun",
                    savedRun.getId(), Map.of("runId", savedRun.getId(), "status", "RUNNING"));

            scheduleInitialDispatch(savedRun);

            return savedRun;
        } finally {
            ExecutionMdc.clearRunId();
        }
    }

    /**
     * Schedules the first dispatch pass only after the run's rows are committed.
     *
     * <p>The consumer that receives a dispatch message re-loads the job as a fresh
     * row and immediately flips it to RUNNING. If the message were published from
     * inside this transaction, a fast consumer could read the run before the stage
     * and job creates above had committed and fail with {@code PipelineJob not
     * found}; the message would be dead-lettered and the job left QUEUED forever.
     * Deferring the pass to AFTER_COMMIT makes the visible ordering deterministic:
     * the rows exist before the first message can.
     */
    private void scheduleInitialDispatch(PipelineRun savedRun) {
        UUID runId = savedRun.getId();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No surrounding transaction (direct call or unit test): dispatch now.
            jobDispatcherService.dispatchReadyJobs(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // REQUIRES_NEW: the completing transaction is still bound to this
                // thread, and REQUIRED repository calls would join its already
                // committed context and fail with "no transaction is in progress".
                jobDispatcherService.dispatchReadyJobsInNewTransaction(runId);
            }
        });
    }

    /**
     * Records the outcome of one job attempt and advances the run.
     *
     * <p>Runs under the run's scheduler lock, so parallel branches of the same run
     * serialise their bookkeeping while still executing concurrently.
     */
    public void handleJobCompletion(UUID jobId, boolean success, int exitCode,
                                    String workerId, Instant startedAt, Instant finishedAt) {
        UUID runId = preResolveRunId(jobId);
        if (runId == null) {
            log.warn("[JOB_RESULT_ORPHAN] jobId={} — job or run not found", jobId);
            return;
        }
        runLocks.withRunLock(runId, () -> {
            applyJobCompletion(jobId, success, exitCode, workerId, startedAt, finishedAt);
            return null;
        });
    }

    private void applyJobCompletion(UUID jobId, boolean success, int exitCode,
                                    String workerId, Instant startedAt, Instant finishedAt) {
        ExecutionMdc.setJobId(jobId);
        ExecutionMdc.setWorkerId(workerId);
        try {
            PipelineJob job = pipelineJobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("PipelineJob not found with id: " + jobId));

            PipelineStage stage = job.getPipelineStage();
            PipelineRun run = stage == null ? null : stage.getPipelineRun();
            if (run == null) {
                // Without a run there is nothing to settle or schedule; the job
                // result is still recorded above.
                log.warn("[JOB_RESULT_ORPHANED] jobId={}, reason=job-has-no-pipeline-run", jobId);
                return;
            }
            UUID runId = run.getId();
            ExecutionMdc.setStageId(stage.getId());
            ExecutionMdc.setRunId(runId);

            if (job.getStatus().isTerminal()) {
                log.info("[JOB_RESULT_IGNORED] jobId={}, status={}, reason=already-terminal",
                        jobId, job.getStatus());
                return;
            }

            job.setStatus(success ? PipelineJob.JobStatus.SUCCESS : PipelineJob.JobStatus.FAILED);
            job.setExitCode(exitCode);
            job.setWorkerId(workerId);
            job.setStartedAt(startedAt);
            job.setFinishedAt(finishedAt);
            pipelineJobRepository.save(job);

            List<JobAttempt> attempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
            JobAttempt latestAttempt = null;
            if (!attempts.isEmpty()) {
                latestAttempt = attempts.get(attempts.size() - 1);
                if (!latestAttempt.getStatus().isTerminal()) {
                    latestAttempt.setStatus(success ? JobAttempt.AttemptStatus.SUCCESS : JobAttempt.AttemptStatus.FAILED);
                    latestAttempt.setExitCode(exitCode);
                    latestAttempt.setFinishedAt(finishedAt);
                    jobAttemptRepository.save(latestAttempt);
                }
            }

            if (success) {
                log.info("[JOB_COMPLETED] jobId={}, jobName={}, attemptNumber={}, exitCode={}, status=SUCCESS",
                        jobId, job.getName(), latestAttempt != null ? latestAttempt.getAttemptNumber() : "-", exitCode);
            } else {
                log.warn("[JOB_FAILED] jobId={}, jobName={}, attemptNumber={}, exitCode={}, status=FAILED",
                        jobId, job.getName(), latestAttempt != null ? latestAttempt.getAttemptNumber() : "-", exitCode);
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("jobId", job.getId());
            payload.put("success", success);
            payload.put("exitCode", exitCode);
            if (workerId != null) {
                payload.put("workerId", workerId);
            }

            outboxEventService.publishEvent("JOB_COMPLETED", "PipelineJob", job.getId(), payload);

            if (!success && workspaceConfig.isRetryEnabled() && shouldRetry(attempts)) {
                int nextAttempt = attempts.size() + 1;
                if (nextAttempt < workspaceConfig.getMaxRetries()) {
                    log.info("[JOB_RETRY] jobId={}, attempt={}, maxRetries={}",
                            jobId, nextAttempt, workspaceConfig.getMaxRetries());
                    jobDispatcherService.dispatchForRetry(job, nextAttempt);
                    return;
                }
                log.info("[JOB_RETRY_EXHAUSTED] jobId={}, attempts={}, maxRetries={}",
                        jobId, attempts.size(), workspaceConfig.getMaxRetries());
            }

            // Settle first so a run whose last event was this failure reaches its
            // terminal state, then schedule whatever the graph now allows.
            runStateSettler.settleRun(runId);
            if (isRunActive(runId)) {
                jobDispatcherService.dispatchReadyJobs(runId);
            }
        } finally {
            ExecutionMdc.clearAll();
        }
    }

    private boolean shouldRetry(List<JobAttempt> attempts) {
        return attempts.size() + 1 < workspaceConfig.getMaxRetries();
    }

    private boolean isRunActive(UUID runId) {
        return pipelineRunRepository.findById(runId)
                .map(run -> run.getStatus() == PipelineRun.RunStatus.RUNNING)
                .orElse(false);
    }

    private UUID preResolveRunId(UUID jobId) {
        return pipelineJobRepository.findById(jobId)
                .map(PipelineJob::getPipelineStage)
                .map(PipelineStage::getPipelineRun)
                .map(PipelineRun::getId)
                .orElse(null);
    }

    public void cancelRun(UUID runId) {
        runLocks.withRunLock(runId, () -> {
            applyCancelRun(runId);
            return null;
        });
    }

    private void applyCancelRun(UUID runId) {
        ExecutionMdc.setRunId(runId);
        try {
            log.info("[RUN_CANCELLED] runId={}, status=CANCELLING", runId);

            PipelineRun run = pipelineRunRepository.findById(runId)
                    .orElseThrow(() -> new ResourceNotFoundException("PipelineRun not found with id: " + runId));

            run.setStatus(PipelineRun.RunStatus.CANCELLED);
            run.setFinishedAt(Instant.now());
            pipelineRunRepository.save(run);

            List<PipelineStage> stages = pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId);
            for (PipelineStage stage : stages) {
                ExecutionMdc.setStageId(stage.getId());
                List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stage.getId());
                for (PipelineJob job : jobs) {
                    ExecutionMdc.setJobId(job.getId());
                    if (job.getStatus() == PipelineJob.JobStatus.PENDING
                            || job.getStatus() == PipelineJob.JobStatus.QUEUED
                            || job.getStatus() == PipelineJob.JobStatus.RUNNING) {
                        job.setStatus(PipelineJob.JobStatus.CANCELLED);
                        job.setFinishedAt(Instant.now());
                        pipelineJobRepository.save(job);
                        log.info("[JOB_CANCELLED] jobId={}, jobName={}", job.getId(), job.getName());

                        List<JobAttempt> jobAttempts =
                                jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
                        for (JobAttempt attempt : jobAttempts) {
                            ExecutionMdc.setAttemptId(attempt.getId());
                            if (attempt.getStatus() == JobAttempt.AttemptStatus.PENDING
                                    || attempt.getStatus() == JobAttempt.AttemptStatus.RUNNING) {
                                attempt.setStatus(JobAttempt.AttemptStatus.CANCELLED);
                                attempt.setFinishedAt(Instant.now());
                                jobAttemptRepository.save(attempt);
                                log.info("[ATTEMPT_CANCELLED] attemptId={}, attemptNumber={}",
                                        attempt.getId(), attempt.getAttemptNumber());
                            }
                        }
                        ExecutionMdc.clearAttemptId();
                        ExecutionMdc.clearJobId();
                    }
                }

                if (!stage.getStatus().isTerminal()) {
                    stage.setStatus(PipelineStage.StageStatus.SKIPPED);
                    stage.setFinishedAt(Instant.now());
                    pipelineStageRepository.save(stage);
                    log.info("[STAGE_SKIPPED] stageId={}, stageName={}", stage.getId(), stage.getName());
                }
                ExecutionMdc.clearStageId();
            }

            outboxEventService.publishEvent("RUN_CANCELLED", "PipelineRun",
                    runId, Map.of("runId", runId, "status", "CANCELLED"));
        } finally {
            ExecutionMdc.clearAll();
        }
    }
}
