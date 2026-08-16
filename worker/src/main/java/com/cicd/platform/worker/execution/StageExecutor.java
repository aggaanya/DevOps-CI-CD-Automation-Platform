package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.domain.JobResult;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.StageResult;
import com.cicd.platform.worker.exception.CommandExecutionException;
import com.cicd.platform.worker.exception.PipelineExecutionException;
import com.cicd.platform.worker.logging.MdcContext;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.StageDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes a stage: its jobs sequentially. A failed job stops the remaining
 * jobs of the stage and, via the returned status, the pipeline.
 */
@Component
public class StageExecutor {

    private static final Logger log = LoggerFactory.getLogger(StageExecutor.class);

    private final JobExecutor jobExecutor;

    public StageExecutor(JobExecutor jobExecutor) {
        this.jobExecutor = jobExecutor;
    }

    public StageResult execute(ExecutionContext ctx, StageDefinition stage) {
        MdcContext.put(MdcContext.STAGE, stage.name());
        log.info("Starting stage [{}] with {} job(s)", stage.name(), stage.jobs().size());
        ctx.logs().log("=== STAGE: " + stage.name() + " ===");
        Instant startedAt = Instant.now();
        List<JobResult> jobResults = new ArrayList<>();
        JobStatus finalStatus = JobStatus.SUCCESS;
        String error = null;

        try {
            for (JobDefinition job : stage.jobs()) {
                if (ctx.isCancelled()) {
                    finalStatus = JobStatus.CANCELLED;
                    error = "Pipeline cancelled: " + ctx.cancellationReason();
                    break;
                }
                JobResult result = jobExecutor.execute(ctx, job);
                jobResults.add(result);
                if (result.status() == JobStatus.FAILED || result.status() == JobStatus.TIMED_OUT
                        || result.status() == JobStatus.CANCELLED) {
                    finalStatus = result.status();
                    error = "Job '" + result.name() + "' " + result.status();
                    break;
                }
            }

            Instant completedAt = Instant.now();
            ctx.logs().log("=== STAGE END: " + stage.name() + " -> " + finalStatus + " ===");
            return new StageResult(stage.name(), finalStatus, startedAt, completedAt,
                    duration(startedAt, completedAt), jobResults, error);
        } catch (CommandExecutionException | PipelineExecutionException e) {
            throw e;
        } finally {
            MdcContext.remove(MdcContext.STAGE);
        }
    }

    private long duration(Instant start, Instant end) {
        return Math.max(0L, end.toEpochMilli() - start.toEpochMilli());
    }
}
