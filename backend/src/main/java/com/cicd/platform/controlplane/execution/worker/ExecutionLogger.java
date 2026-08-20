package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.ExecutionContext;
import com.cicd.platform.controlplane.execution.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ExecutionLogger {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogger.class);

    public void logJobStart(ExecutionContext ctx) {
        String safeGitUrl = GitOperations.sanitizeUrl(ctx.gitUrl());
        log.info("[JOB_START] runId={}, jobId={}, jobName={}, jobType={}, attempt={}, workerId={}, branch={}, commitSha={}, gitUrl={}",
                ctx.runId(),
                ctx.jobId(),
                ctx.jobName(),
                ctx.jobType(),
                ctx.attemptNumber(),
                ctx.workerId(),
                ctx.branch(),
                ctx.commitSha(),
                safeGitUrl);
    }

    public void logJobComplete(ExecutionContext ctx, boolean success, int exitCode) {
        if (success) {
            log.info("[JOB_COMPLETE] runId={}, jobId={}, jobName={}, attempt={}, exitCode={}, status=SUCCESS",
                    ctx.runId(),
                    ctx.jobId(),
                    ctx.jobName(),
                    ctx.attemptNumber(),
                    exitCode);
        } else {
            log.error("[JOB_COMPLETE] runId={}, jobId={}, jobName={}, attempt={}, exitCode={}, status=FAILED",
                    ctx.runId(),
                    ctx.jobId(),
                    ctx.jobName(),
                    ctx.attemptNumber(),
                    exitCode);
        }
    }

    public void logStepExecution(String stepName, StepResult result) {
        if (result.success()) {
            log.info("[STEP_COMPLETE] step={}, exitCode={}, status=SUCCESS",
                    stepName,
                    result.exitCode());
        } else {
            log.warn("[STEP_COMPLETE] step={}, exitCode={}, status=FAILED, stderr={}",
                    stepName,
                    result.exitCode(),
                    result.stderr());
        }
    }

    public void logError(String message, Throwable t) {
        log.error("[ERROR] message={}", message, t);
    }

    public void logCancellation(UUID runId) {
        log.warn("[CANCELLATION] runId={}, pipeline run has been cancelled", runId);
    }
}
