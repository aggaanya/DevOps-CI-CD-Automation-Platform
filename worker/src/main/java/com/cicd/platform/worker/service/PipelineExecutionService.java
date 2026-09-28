package com.cicd.platform.worker.service;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.PipelineResult;
import com.cicd.platform.worker.exception.CommandExecutionException;
import com.cicd.platform.worker.exception.GitOperationException;
import com.cicd.platform.worker.exception.PipelineConfigurationException;
import com.cicd.platform.worker.exception.PipelineExecutionException;
import com.cicd.platform.worker.exception.WorkspaceException;
import com.cicd.platform.worker.execution.ExecutionContext;
import com.cicd.platform.worker.execution.PipelineExecutor;
import com.cicd.platform.worker.git.CommitInfo;
import com.cicd.platform.worker.git.GitService;
import com.cicd.platform.worker.logging.ExecutionLogCollector;
import com.cicd.platform.worker.logging.MdcContext;
import com.cicd.platform.worker.pipeline.PipelineLoader;
import com.cicd.platform.worker.pipeline.PipelineParser;
import com.cicd.platform.worker.pipeline.PipelineValidator;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.PipelineDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import com.cicd.platform.worker.workspace.Workspace;
import com.cicd.platform.worker.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the full lifecycle of one pipeline job:
 *
 * <pre>
 *   validate (consumer) -> workspace -> git clone/checkout -> pipeline load
 *   -> parse -> validate -> execute -> result -> cleanup (always)
 * </pre>
 *
 * <p>Two execution modes:
 * <ul>
 *   <li><b>Legacy mode</b> (manual trigger): loads pipeline.yml from repository,
 *       parses and validates, executes entire pipeline.</li>
 *   <li><b>Control plane mode</b> (DAG dispatch): uses declared steps from
 *       PipelineVersion, executes single job, still checks out repository for source code.</li>
 * </ul>
 *
 * <p>Returns a {@link PipelineResult} for every workload outcome (build/test
 * failures included). Infrastructure failures (git, workspace, sandbox) are
 * thrown so the consumer can classify them for retry.</p>
 */
@Service
public class PipelineExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutionService.class);

    private final WorkerProperties props;
    private final WorkspaceManager workspaceManager;
    private final GitService gitService;
    private final PipelineLoader pipelineLoader;
    private final PipelineParser pipelineParser;
    private final PipelineValidator pipelineValidator;
    private final PipelineExecutor pipelineExecutor;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pipeline-watchdog");
        t.setDaemon(true);
        return t;
    });

    public PipelineExecutionService(WorkerProperties props, WorkspaceManager workspaceManager,
                                    GitService gitService, PipelineLoader pipelineLoader,
                                    PipelineParser pipelineParser, PipelineValidator pipelineValidator,
                                    PipelineExecutor pipelineExecutor) {
        this.props = props;
        this.workspaceManager = workspaceManager;
        this.gitService = gitService;
        this.pipelineLoader = pipelineLoader;
        this.pipelineParser = pipelineParser;
        this.pipelineValidator = pipelineValidator;
        this.pipelineExecutor = pipelineExecutor;
    }

    @PreDestroy
    public void shutdown() {
        watchdog.shutdownNow();
        try {
            watchdog.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public PipelineResult execute(PipelineJob job) {
        Instant startedAt = Instant.now();
        MdcContext.putJob(props.getId(), job.jobId(), job.pipelineId(),
                job.repositoryUrl(), job.commitSha());
        log.info("Received job: repository={}, commit={}, mode={}",
                redactedUrl(job.repositoryUrl()), job.commitSha(),
                job.hasDeclaredSteps() ? "control-plane" : "legacy");

        Workspace workspace = null;
        ExecutionContext ctx = null;
        try {
            workspace = workspaceManager.create(job);
            ctx = new ExecutionContext(job, workspace,
                    new ExecutionLogCollector(workspace));

            scheduleWatchdog(ctx, workspace);
            try {
                // Always checkout the repository at the requested commit for source code access
                CommitInfo commit = gitService.checkoutCommit(job, workspace.repoDir());
                log.info("Prepared repository at commit {} (branch {})", commit.commitSha(), commit.branch());

                if (job.hasDeclaredSteps()) {
                    // Control plane mode: execute single job with declared steps
                    return executeSingleJob(ctx, job, commit, startedAt);
                } else {
                    // Legacy mode: load pipeline from repository and execute entire pipeline
                    var pipelineFile = pipelineLoader.locate(workspace.repoDir(), job.pipelineFile());
                    PipelineDefinition pipeline = pipelineParser.parse(pipelineFile);
                    pipelineValidator.validate(pipeline);

                    PipelineExecutor.ExecutionOutcome outcome = pipelineExecutor.execute(ctx, pipeline);
                    return buildResult(job, outcome.status(), outcome.stages(), startedAt,
                            commit.commitSha(), outcomeSummary(outcome));
                }
            } catch (Exception e) {
                log.error("Execution failed for job {}: {}", job.jobId(), safeMessage(e));
                throw e;
            } finally {
                cancelWatchdog();
            }
        } catch (PipelineConfigurationException e) {
            log.warn("Pipeline configuration error for job {}: {}", job.jobId(), e.getMessage());
            return buildResult(job, JobStatus.FAILED, List.of(), startedAt,
                    job.commitSha(), e.getMessage());
        } catch (GitOperationException | WorkspaceException | CommandExecutionException | PipelineExecutionException e) {
            log.error("Infrastructure failure for job {}: {}", job.jobId(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("Unexpected failure for job {}: {}", job.jobId(), safeMessage(e), e);
            throw new PipelineExecutionException("Unexpected execution failure: " + safeMessage(e), e);
        } finally {
            if (ctx != null) {
                try {
                    ctx.logs().log("=== WORKSPACE CLEANUP ===");
                } catch (Exception e) {
                    log.debug("Failed to log cleanup message: {}", e.getMessage());
                }
            }
            try {
                workspaceManager.cleanup(workspace);
            } catch (Exception e) {
                log.warn("Failed to cleanup workspace for job {}: {}", job.jobId(), e.getMessage());
            }
            MdcContext.clear();
        }
    }

    /**
     * Executes a single job using the declared steps from the control plane.
     * The repository is already checked out at the correct commit.
     * Creates a synthetic single-job pipeline and delegates to PipelineExecutor.
     */
    private PipelineResult executeSingleJob(ExecutionContext ctx, PipelineJob job, CommitInfo commit, Instant startedAt) {
        ctx.logs().log("=== JOB: " + job.jobName() + " (control plane dispatch) ===");
        Instant jobStartedAt = Instant.now();

        List<PipelineJob.WorkerStepDefinition> workerSteps = job.steps();

        // Convert worker steps to pipeline model steps
        List<StepDefinition> steps = workerSteps.stream().map(ws -> {
            String stepName = ws.name() != null && !ws.name().isBlank()
                    ? ws.name() : "step";
            return new StepDefinition(StepType.RUN, stepName, ws.run(), null);
        }).toList();

        // Create synthetic single-job, single-stage pipeline
        JobDefinition jobDef = new JobDefinition(
                job.jobName(),
                null,           // workingDirectory
                null,           // image
                Map.of(),       // env
                steps,          // steps
                List.of()       // artifacts
        );

        com.cicd.platform.worker.pipeline.model.StageDefinition stageDef =
                new com.cicd.platform.worker.pipeline.model.StageDefinition(job.jobName(), List.of(jobDef));

        PipelineDefinition syntheticPipeline = new PipelineDefinition(
                job.jobName(),
                List.of(stageDef),
                "control-plane-dispatch"
        );

        // Execute via PipelineExecutor
        PipelineExecutor.ExecutionOutcome outcome = pipelineExecutor.execute(ctx, syntheticPipeline);

        // Convert outcome to PipelineResult
        JobStatus finalStatus = outcome.status();
        String message = finalStatus == JobStatus.SUCCESS ? "Job completed successfully"
                : outcome.stages().stream()
                .flatMap(s -> s.jobs().stream())
                .filter(j -> j.status() != JobStatus.SUCCESS)
                .findFirst()
                .map(j -> "Job '" + j.name() + "' " + j.status() + (j.error() != null ? ": " + j.error() : ""))
                .orElse(finalStatus.name());

        Instant completedAt = Instant.now();
        return new PipelineResult(job.jobId(), job.pipelineId(), finalStatus, props.getId(),
                redactedUrl(job.repositoryUrl()), commit.commitSha(), job.branch(),
                startedAt, completedAt, Math.max(0L, completedAt.toEpochMilli() - startedAt.toEpochMilli()),
                outcome.stages(), message);
    }

    private void scheduleWatchdog(ExecutionContext ctx, Workspace workspace) {
        long maxMs = props.getMaxPipelineDurationMs();
        watchdog.schedule(() -> {
            if (!ctx.isCancelled()) {
                log.error("Pipeline exceeded maximum duration of {} ms; terminating", maxMs);
                ctx.cancel("pipeline exceeded maximum duration of " + maxMs + " ms");
            }
        }, maxMs, TimeUnit.MILLISECONDS);
    }

    private void cancelWatchdog() {
        // Tasks are one-shot; nothing to cancel. Kept for future watchdog reuse.
    }

    private PipelineResult buildResult(PipelineJob job, JobStatus status, List<com.cicd.platform.worker.domain.StageResult> stages,
                                       Instant startedAt, String commitSha, String message) {
        Instant completedAt = Instant.now();
        return new PipelineResult(job.jobId(), job.pipelineId(), status, props.getId(),
                redactedUrl(job.repositoryUrl()), commitSha, job.branch(),
                startedAt, completedAt, Math.max(0L, completedAt.toEpochMilli() - startedAt.toEpochMilli()),
                stages, message);
    }

    private String outcomeSummary(PipelineExecutor.ExecutionOutcome outcome) {
        return outcome.status() == JobStatus.SUCCESS
                ? "Pipeline completed successfully"
                : "Pipeline " + outcome.status() + ": "
                + outcome.stages().stream()
                .filter(s -> s.status() != JobStatus.SUCCESS)
                .findFirst()
                .map(s -> "stage '" + s.name() + "' - " + (s.error() == null ? s.status() : s.error()))
                .orElse(outcome.status().name());
    }

    private String redactedUrl(String url) {
        if (url == null) {
            return "<null>";
        }
        return url.replaceAll("(https?://)([^@/]+)@", "$1<redacted>@");
    }

    private String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}