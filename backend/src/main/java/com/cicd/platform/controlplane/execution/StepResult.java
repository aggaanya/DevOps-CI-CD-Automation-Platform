package com.cicd.platform.controlplane.execution;

import java.time.Instant;

public record StepResult(
        String stepName,
        boolean success,
        int exitCode,
        String stdout,
        String stderr,
        Instant startedAt,
        Instant finishedAt
) {
    public static StepResult success(String stepName, String stdout, Instant startedAt, Instant finishedAt) {
        return new StepResult(stepName, true, 0, stdout, "", startedAt, finishedAt);
    }

    public static StepResult failure(String stepName, int exitCode, String stderr,
                                     Instant startedAt, Instant finishedAt) {
        return new StepResult(stepName, false, exitCode, "", stderr, startedAt, finishedAt);
    }
}
