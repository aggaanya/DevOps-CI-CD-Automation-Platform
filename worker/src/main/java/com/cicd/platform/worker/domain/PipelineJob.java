package com.cicd.platform.worker.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable contract of a pipeline execution job received from RabbitMQ.
 *
 * <p>The contract is intentionally typed and extensible. {@code environment}
 * carries trusted variables supplied by the backend; {@code metadata} is an
 * open extension point for future fields (labels, priorities, requester, ...).</p>
 *
 * @param jobId          unique id of this execution (used for idempotency).
 * @param pipelineId     logical pipeline identifier the job belongs to.
 * @param repositoryUrl  git repository URL to clone.
 * @param commitSha      exact commit that must be built (never "latest").
 * @param branch         branch the commit belongs to (informational).
 * @param pipelineFile   path of the pipeline YAML inside the repository.
 * @param environment    trusted environment variables passed to every step.
 * @param metadata       extension point for future job fields.
 * @param createdAt      when the job was created by the backend.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineJob(
        String jobId,
        String pipelineId,
        String repositoryUrl,
        String commitSha,
        String branch,
        String pipelineFile,
        Map<String, String> environment,
        Map<String, Object> metadata,
        Instant createdAt) {

    public PipelineJob {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
