package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.domain.JobResult;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.StepResult;
import com.cicd.platform.worker.exception.CommandExecutionException;
import com.cicd.platform.worker.exception.PipelineExecutionException;
import com.cicd.platform.worker.logging.MdcContext;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes a job: its ordered steps, stopping on the first failure (no
 * continue-on-error in Phase 4). Collects artifacts after success.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final StepExecutor stepExecutor;
    private final ArtifactCollector artifactCollector;

    public JobExecutor(StepExecutor stepExecutor, ArtifactCollector artifactCollector) {
        this.stepExecutor = stepExecutor;
        this.artifactCollector = artifactCollector;
    }

    public JobResult execute(ExecutionContext ctx, JobDefinition job) {
        MdcContext.put(MdcContext.JOB, job.name());
        log.info("Starting job [{}] with {} step(s)", job.name(), job.steps().size());
        ctx.logs().log("=== JOB: " + job.name() + " ===");
        Instant startedAt = Instant.now();
        List<StepResult> stepResults = new ArrayList<>();
        JobStatus finalStatus = JobStatus.SUCCESS;
        String error = null;

        try {
            for (int i = 0; i < job.steps().size(); i++) {
                StepDefinition step = job.steps().get(i);
                if (ctx.isCancelled()) {
                    finalStatus = JobStatus.CANCELLED;
                    error = "Pipeline cancelled: " + ctx.cancellationReason();
                    break;
                }
                StepResult result = stepExecutor.execute(ctx, job, step, i);
                stepResults.add(result);
                if (result.status() == JobStatus.FAILED || result.status() == JobStatus.TIMED_OUT
                        || result.status() == JobStatus.CANCELLED) {
                    finalStatus = result.status();
                    error = "Step '" + result.name() + "' " + result.status();
                    if (result.error() != null) {
                        error = result.error();
                    }
                    break;
                }
            }

            if (finalStatus == JobStatus.SUCCESS && !job.artifacts().isEmpty()) {
                ctx.logs().log("Collecting artifacts for job " + job.name());
                artifactCollector.collect(ctx.workspace(), job);
            }

            Instant completedAt = Instant.now();
            ctx.logs().log("=== JOB END: " + job.name() + " -> " + finalStatus + " ===");
            return new JobResult(job.name(), finalStatus, startedAt, completedAt,
                    duration(startedAt, completedAt), stepResults, null, error);
        } catch (CommandExecutionException | PipelineExecutionException e) {
            throw e;
        } finally {
            MdcContext.remove(MdcContext.JOB);
        }
    }

    private long duration(Instant start, Instant end) {
        return Math.max(0L, end.toEpochMilli() - start.toEpochMilli());
    }
}
