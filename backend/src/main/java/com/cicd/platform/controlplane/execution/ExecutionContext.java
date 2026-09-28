package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StepDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Everything a worker needs to execute one attempt of one job.
 *
 * @param steps explicit step commands declared by the pipeline; empty means the
 *              worker falls back to build-system auto-detection, which is the
 *              behaviour of every pipeline authored before steps were supported
 */
public record ExecutionContext(
        UUID jobId,
        UUID runId,
        String jobName,
        PipelineJob.JobType jobType,
        Path workspacePath,
        Path workDir,
        Path logsDir,
        Path artifactsDir,
        String gitUrl,
        String branch,
        String commitSha,
        int attemptNumber,
        long timeoutSeconds,
        String workerId,
        List<StepDefinition> steps
) {
    public ExecutionContext(UUID jobId, UUID runId, String jobName,
                            PipelineJob.JobType jobType, Path workspacePath, Path workDir,
                            Path logsDir, Path artifactsDir, String gitUrl, String branch,
                            String commitSha, int attemptNumber, long timeoutSeconds,
                            String workerId) {
        this(jobId, runId, jobName, jobType, workspacePath, workDir, logsDir, artifactsDir,
                gitUrl, branch, commitSha, attemptNumber, timeoutSeconds, workerId, List.of());
    }
}
