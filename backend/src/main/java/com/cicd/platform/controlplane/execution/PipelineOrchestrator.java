package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StageDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.JobDefinition;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.parser.PipelineYamlParser;
import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@Transactional
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);
    private static final String[] PAYLOAD_KEYS = {"runId", "jobId", "stageId", "status",
            "success", "exitCode", "workerId"};

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final PipelineJobRepository pipelineJobRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final PipelineYamlParser pipelineYamlParser = new PipelineYamlParser();
    private final PipelineConfigMapper pipelineConfigMapper = new PipelineConfigMapper();
    private final JobDispatcherService jobDispatcherService;
    private final StageResultCollector stageResultCollector;
    private final WorkspaceConfig workspaceConfig;
    private final OutboxEventService outboxEventService;
    private final ExecutorService jobExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors());

    public PipelineOrchestrator(
            PipelineRunRepository pipelineRunRepository,
            PipelineStageRepository pipelineStageRepository,
            PipelineJobRepository pipelineJobRepository,
            PipelineVersionRepository pipelineVersionRepository,
            JobAttemptRepository jobAttemptRepository,
            JobDispatcherService jobDispatcherService,
            StageResultCollector stageResultCollector,
            WorkspaceConfig workspaceConfig,
            OutboxEventService outboxEventService) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineStageRepository = pipelineStageRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobDispatcherService = jobDispatcherService;
        this.stageResultCollector = stageResultCollector;
        this.workspaceConfig = workspaceConfig;
        this.outboxEventService = outboxEventService;
    }

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
                stage.setPipelineRun(run);
                stage = pipelineStageRepository.save(stage);
                log.info("[STAGE_CREATED] stageId={}, stageName={}, orderIndex={}, status=PENDING",
                        stage.getId(), stage.getName(), stage.getOrderIndex());

                for (JobDefinition jobDef : stageDef.jobs()) {
                    PipelineJob job = new PipelineJob();
                    job.setName(jobDef.name());
                    job.setJobType(jobDef.jobType());
                    job.setStatus(PipelineJob.JobStatus.PENDING);
                    job.setPipelineStage(stage);
                    pipelineJobRepository.save(job);
                }
            }

            run.setStatus(PipelineRun.RunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            PipelineRun savedRun = pipelineRunRepository.save(run);

            outboxEventService.publishEvent("RUN_STARTED", "PipelineRun",
                    savedRun.getId(), Map.of("runId", savedRun.getId(), "status", "RUNNING"));

            jobDispatcherService.dispatchReadyJobs(savedRun.getId());

            return savedRun;
        } finally {
            ExecutionMdc.clearRunId();
        }
    }

    public void handleJobCompletion(UUID jobId, boolean success, int exitCode,
                                    String workerId, Instant startedAt, Instant finishedAt) {
        ExecutionMdc.setJobId(jobId);
        ExecutionMdc.setWorkerId(workerId);
        try {
            PipelineJob job = pipelineJobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("PipelineJob not found with id: " + jobId));

            PipelineStage stage = job.getPipelineStage();
            if (stage != null) {
                ExecutionMdc.setStageId(stage.getId());
                ExecutionMdc.setRunId(stage.getPipelineRun().getId());
            }

            if (job.getStatus() == PipelineJob.JobStatus.SUCCESS
                    || job.getStatus() == PipelineJob.JobStatus.FAILED
                    || job.getStatus() == PipelineJob.JobStatus.CANCELLED) {
                log.info("[JOB_SKIPPED] jobId={}, status={}, reason=already-terminal",
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
                if (latestAttempt.getStatus() != JobAttempt.AttemptStatus.SUCCESS
                        && latestAttempt.getStatus() != JobAttempt.AttemptStatus.FAILED
                        && latestAttempt.getStatus() != JobAttempt.AttemptStatus.CANCELLED) {
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

            outboxEventService.publishEvent("JOB_COMPLETED", "PipelineJob",
                    job.getId(), payload);

            if (stage != null && isStageComplete(stage)) {
                PipelineStage.StageStatus stageStatus = stageResultCollector.evaluateStageStatus(
                        stage, pipelineJobRepository.findByPipelineStageId(stage.getId()));
                stage.setStatus(stageStatus);
                stage.setStartedAt(startedAt);
                stage.setFinishedAt(finishedAt);
                pipelineStageRepository.save(stage);

                log.info("[STAGE_COMPLETED] stageId={}, stageName={}, status={}",
                        stage.getId(), stage.getName(), stageStatus);

                outboxEventService.publishEvent("STAGE_COMPLETED", "PipelineStage",
                        stage.getId(), Map.of(
                                "stageId", stage.getId(),
                                "status", stageStatus.name()));

                PipelineRun run = stage.getPipelineRun();
                if (isAllStagesComplete(run)) {
                    List<PipelineStage> allStages = pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
                    PipelineRun.RunStatus runStatus = stageResultCollector.evaluateRunStatus(allStages);
                    run.setStatus(runStatus);
                    run.setFinishedAt(Instant.now());
                    pipelineRunRepository.save(run);

                    log.info("[RUN_COMPLETED] runId={}, status={}", run.getId(), runStatus);

                    outboxEventService.publishEvent("RUN_COMPLETED", "PipelineRun",
                            run.getId(), Map.of(
                                    "runId", run.getId(),
                                    "status", runStatus.name()));
                }
            }

            if (!success && workspaceConfig.isRetryEnabled()) {
                long attemptCount = jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()).size();
                int nextAttempt = (int) attemptCount + 1;
                if (nextAttempt < workspaceConfig.getMaxRetries()) {
                    log.info("[JOB_RETRY] jobId={}, attempt={}, maxRetries={}",
                            jobId, nextAttempt, workspaceConfig.getMaxRetries());
                    jobDispatcherService.dispatchForRetry(job, nextAttempt);
                    return;
                } else {
                    log.info("[JOB_RETRY_EXHAUSTED] jobId={}, attempt={}, maxRetries={}",
                            jobId, attemptCount, workspaceConfig.getMaxRetries());
                }
            }

            if (stage != null) {
                PipelineRun run = stage.getPipelineRun();
                if (run.getStatus() == PipelineRun.RunStatus.RUNNING) {
                    jobDispatcherService.dispatchReadyJobs(run.getId());
                }
            }
        } finally {
            ExecutionMdc.clearAll();
        }
    }

    public void cancelRun(UUID runId) {
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
                            || job.getStatus() == PipelineJob.JobStatus.QUEUED) {
                        job.setStatus(PipelineJob.JobStatus.CANCELLED);
                        job.setFinishedAt(Instant.now());
                        pipelineJobRepository.save(job);
                        log.info("[JOB_CANCELLED] jobId={}, jobName={}, previousStatus={}",
                                job.getId(), job.getName(), job.getStatus());

                        List<JobAttempt> jobAttempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
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

                if (stage.getStatus() == PipelineStage.StageStatus.PENDING) {
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

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down pipeline orchestrator, waiting for in-flight jobs");
        jobExecutor.shutdown();
        try {
            if (!jobExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                jobExecutor.shutdownNow();
                log.warn("Forced shutdown of pipeline orchestrator executor");
            }
        } catch (InterruptedException e) {
            jobExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Pipeline orchestrator shut down");
    }

    private boolean isStageComplete(PipelineStage stage) {
        List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stage.getId());
        return jobs.stream().allMatch(job ->
                job.getStatus() == PipelineJob.JobStatus.SUCCESS
                        || job.getStatus() == PipelineJob.JobStatus.FAILED
                        || job.getStatus() == PipelineJob.JobStatus.CANCELLED);
    }

    private boolean isAllStagesComplete(PipelineRun run) {
        List<PipelineStage> stages = pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        return stages.stream().allMatch(stage ->
                stage.getStatus() == PipelineStage.StageStatus.SUCCESS
                        || stage.getStatus() == PipelineStage.StageStatus.FAILED
                        || stage.getStatus() == PipelineStage.StageStatus.SKIPPED);
    }
}
