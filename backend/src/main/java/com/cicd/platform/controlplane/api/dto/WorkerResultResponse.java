package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.WorkerResult;

import java.time.Instant;
import java.util.UUID;

public record WorkerResultResponse(
        UUID id,
        String jobId,
        String pipelineId,
        String status,
        String workerId,
        String repositoryUrl,
        String commitSha,
        String branch,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String message,
        Instant receivedAt
) {
    public static WorkerResultResponse from(WorkerResult result) {
        return new WorkerResultResponse(
                result.getId(),
                result.getJobId(),
                result.getPipelineId(),
                result.getStatus(),
                result.getWorkerId(),
                result.getRepositoryUrl(),
                result.getCommitSha(),
                result.getBranch(),
                result.getStartedAt(),
                result.getCompletedAt(),
                result.getDurationMs(),
                result.getMessage(),
                result.getReceivedAt()
        );
    }
}