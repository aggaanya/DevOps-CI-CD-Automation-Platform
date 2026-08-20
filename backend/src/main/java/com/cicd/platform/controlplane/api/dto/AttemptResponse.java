package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;

import java.time.Instant;
import java.util.UUID;

public record AttemptResponse(
        UUID id,
        UUID jobId,
        Integer attemptNumber,
        String status,
        Integer exitCode,
        String logsLocation,
        Instant startedAt,
        Instant finishedAt
) {
    public static AttemptResponse from(JobAttempt attempt) {
        return new AttemptResponse(
                attempt.getId(),
                attempt.getJob().getId(),
                attempt.getAttemptNumber(),
                attempt.getStatus().name(),
                attempt.getExitCode(),
                attempt.getLogsLocation(),
                attempt.getStartedAt(),
                attempt.getFinishedAt()
        );
    }
}
