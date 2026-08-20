package com.cicd.platform.controlplane.execution.message;

import java.time.Instant;
import java.util.UUID;

public record JobResultMessage(
        UUID jobId,
        UUID runId,
        UUID attemptId,
        int attemptNumber,
        boolean success,
        int exitCode,
        String workerId,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {}
