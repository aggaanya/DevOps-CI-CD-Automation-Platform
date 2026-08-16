package com.cicd.platform.worker.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StageResult(
        String name,
        JobStatus status,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        List<JobResult> jobs,
        String error) {

    public static StageResult cancelled(String name, Instant startedAt, Instant completedAt, String reason) {
        return new StageResult(name, JobStatus.CANCELLED, startedAt, completedAt,
                duration(startedAt, completedAt), List.of(), reason);
    }

    private static long duration(Instant start, Instant end) {
        return end == null || start == null ? 0L : Math.max(0L, end.toEpochMilli() - start.toEpochMilli());
    }
}
