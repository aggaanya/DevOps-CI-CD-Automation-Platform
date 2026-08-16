package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.logging.ExecutionLogCollector;
import com.cicd.platform.worker.workspace.Workspace;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-run context shared by the executors: workspace, job, trusted
 * environment and the cancellation flag set by the pipeline watchdog.
 */
public class ExecutionContext {

    private final PipelineJob job;
    private final Workspace workspace;
    private final ExecutionLogCollector logCollector;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String cancellationReason;

    public ExecutionContext(PipelineJob job, Workspace workspace, ExecutionLogCollector logCollector) {
        this.job = job;
        this.workspace = workspace;
        this.logCollector = logCollector;
    }

    public PipelineJob job() {
        return job;
    }

    public Workspace workspace() {
        return workspace;
    }

    public ExecutionLogCollector logs() {
        return logCollector;
    }

    /**
     * Merged environment with precedence low → high:
     * base (worker config) → pipeline YAML → job message (backend, trusted).
     */
    public Map<String, String> trustedEnvironment(Map<String, String> pipelineEnv, Map<String, String> baseEnv) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        if (baseEnv != null) {
            merged.putAll(baseEnv);
        }
        if (pipelineEnv != null) {
            merged.putAll(pipelineEnv);
        }
        if (job.environment() != null) {
            merged.putAll(job.environment());
        }
        return Map.copyOf(merged);
    }

    public void cancel(String reason) {
        cancellationReason = reason;
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String cancellationReason() {
        return cancellationReason;
    }
}
