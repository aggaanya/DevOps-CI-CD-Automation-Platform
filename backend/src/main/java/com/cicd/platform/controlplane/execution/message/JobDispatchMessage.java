package com.cicd.platform.controlplane.execution.message;

import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StepDefinition;

import java.util.List;
import java.util.UUID;

/**
 * The message published to {@code pipeline-jobs} and consumed by the worker.
 *
 * <p>Contract note: the message carries everything the worker needs to execute a
 * job without reading the database, so a worker never has to trust the control
 * plane's persistence layer mid-flight.
 */
public record JobDispatchMessage(
        UUID jobId,
        UUID runId,
        UUID pipelineVersionId,
        String jobName,
        String jobType,
        String gitUrl,
        String branch,
        String commitSha,
        int attemptNumber,
        int version,
        UUID correlationId,
        List<StepDefinition> steps
) {
    public JobDispatchMessage(UUID jobId, UUID runId, UUID pipelineVersionId,
                              String jobName, String jobType, String gitUrl,
                              String branch, String commitSha, int attemptNumber,
                              int version, UUID correlationId) {
        this(jobId, runId, pipelineVersionId, jobName, jobType, gitUrl, branch, commitSha,
                attemptNumber, version, correlationId, List.of());
    }

    public static JobDispatchMessage create(UUID jobId, UUID runId, UUID pipelineVersionId,
                                            String jobName, String jobType, String gitUrl,
                                            String branch, String commitSha, int attemptNumber) {
        return create(jobId, runId, pipelineVersionId, jobName, jobType, gitUrl,
                branch, commitSha, attemptNumber, List.of());
    }

    public static JobDispatchMessage create(UUID jobId, UUID runId, UUID pipelineVersionId,
                                            String jobName, String jobType, String gitUrl,
                                            String branch, String commitSha, int attemptNumber,
                                            List<StepDefinition> steps) {
        return new JobDispatchMessage(jobId, runId, pipelineVersionId, jobName, jobType,
                gitUrl, branch, commitSha, attemptNumber, 1, UUID.randomUUID(),
                steps == null ? List.of() : List.copyOf(steps));
    }

    /** True when the pipeline declared explicit step commands for this job. */
    public boolean hasDeclaredSteps() {
        return steps != null && !steps.isEmpty();
    }
}
