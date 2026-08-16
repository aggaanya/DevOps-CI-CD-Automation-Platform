package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.StageResult;
import com.cicd.platform.worker.exception.CommandExecutionException;
import com.cicd.platform.worker.exception.PipelineExecutionException;
import com.cicd.platform.worker.logging.MdcContext;
import com.cicd.platform.worker.pipeline.model.PipelineDefinition;
import com.cicd.platform.worker.pipeline.model.StageDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates Pipeline → Stage → Job → Step execution. Stages run
 * sequentially; a failed stage cancels the remaining stages (CANCELLED).
 */
@Component
public class PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutor.class);

    private final StageExecutor stageExecutor;

    public PipelineExecutor(StageExecutor stageExecutor) {
        this.stageExecutor = stageExecutor;
    }

    /**
     * @return list of stage results (one per stage) and the overall pipeline status.
     */
    public ExecutionOutcome execute(ExecutionContext ctx, PipelineDefinition pipeline) {
        log.info("Executing pipeline '{}' with {} stage(s)", pipeline.name(), pipeline.stages().size());
        ctx.logs().log("=== PIPELINE: " + pipeline.name() + " ===");
        List<StageResult> stageResults = new ArrayList<>();
        JobStatus overall = JobStatus.SUCCESS;

        for (int i = 0; i < pipeline.stages().size(); i++) {
            StageDefinition stage = pipeline.stages().get(i);
            if (ctx.isCancelled()) {
                stageResults.add(StageResult.cancelled(stage.name(),
                        java.time.Instant.now(), java.time.Instant.now(),
                        "Pipeline cancelled: " + ctx.cancellationReason()));
                overall = JobStatus.CANCELLED;
                continue;
            }
            StageResult result = stageExecutor.execute(ctx, stage);
            stageResults.add(result);
            if (result.status() == JobStatus.FAILED || result.status() == JobStatus.TIMED_OUT
                    || result.status() == JobStatus.CANCELLED) {
                overall = result.status();
                String reason = "Stage '" + stage.name() + "' " + result.status()
                        + (result.error() != null ? ": " + result.error() : "");
                ctx.logs().log("PIPELINE STOPPED - " + reason);
                markRemainingCancelled(stageResults, pipeline, i + 1, reason);
                break;
            }
            if (ctx.isCancelled()) {
                overall = JobStatus.CANCELLED;
                markRemainingCancelled(stageResults, pipeline, i + 1, "Pipeline cancelled: " + ctx.cancellationReason());
                break;
            }
        }
        ctx.logs().log("=== PIPELINE END: " + pipeline.name() + " -> " + overall + " ===");
        return new ExecutionOutcome(overall, stageResults);
    }

    private void markRemainingCancelled(List<StageResult> stageResults, PipelineDefinition pipeline,
                                        int fromIndex, String reason) {
        java.time.Instant now = java.time.Instant.now();
        for (int i = fromIndex; i < pipeline.stages().size(); i++) {
            stageResults.add(StageResult.cancelled(pipeline.stages().get(i).name(), now, now,
                    "Not executed due to earlier " + reason));
        }
    }

    /**
     * Result of a pipeline execution.
     */
    public record ExecutionOutcome(JobStatus status, List<StageResult> stages) {
    }
}
