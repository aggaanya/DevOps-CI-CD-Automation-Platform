package com.cicd.platform.worker.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineResult(
        String jobId,
        String pipelineId,
        JobStatus status,
        String workerId,
        String repositoryUrl,
        String commitSha,
        String branch,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        List<StageResult> stages,
        String message) {
}
