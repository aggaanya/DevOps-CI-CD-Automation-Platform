package com.cicd.platform.worker.domain;

import com.cicd.platform.worker.pipeline.model.StepType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StepResult(
        String name,
        StepType type,
        String command,
        JobStatus status,
        int exitCode,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        String stdout,
        String stderr,
        String error) {
}
