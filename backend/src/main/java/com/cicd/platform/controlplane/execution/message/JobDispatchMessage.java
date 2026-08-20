package com.cicd.platform.controlplane.execution.message;

import java.util.UUID;

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
        UUID correlationId
) {
    public static JobDispatchMessage create(UUID jobId, UUID runId, UUID pipelineVersionId,
                                             String jobName, String jobType, String gitUrl,
                                             String branch, String commitSha, int attemptNumber) {
        return new JobDispatchMessage(jobId, runId, pipelineVersionId, jobName, jobType,
                gitUrl, branch, commitSha, attemptNumber, 1, UUID.randomUUID());
    }
}
