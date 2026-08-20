package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;

import java.nio.file.Path;
import java.util.UUID;

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
        String workerId
) {}
