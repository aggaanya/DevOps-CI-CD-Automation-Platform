package com.cicd.platform.controlplane.execution.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Lightweight mirror of the worker's {@code PipelineResult} payload
 * ({@code worker.domain.PipelineResult}). Fields are intentionally reduced —
 * the full raw payload is preserved verbatim on the stored {@code payload}
 * column. {@code status} is kept as a string so the worker's {@code JobStatus}
 * vocabulary (including {@code TIMED_OUT}) is captured without coupling the
 * control plane to the worker's enum.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerResultMessage(
        String jobId,
        String pipelineId,
        String status,
        String workerId,
        String repositoryUrl,
        String commitSha,
        String branch,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        JsonNode stages,
        String message) {}