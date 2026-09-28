package com.cicd.platform.worker.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable contract of a pipeline execution job received from RabbitMQ.
 *
 * <p>The contract is intentionally typed and extensible. {@code environment}
 * carries trusted variables supplied by the backend; {@code metadata} is an
 * open extension point for future fields (labels, priorities, requester, ...).
 * {@code steps} carries the declared step commands from the control plane'"'"'s
 * immutable PipelineVersion, enabling execution without loading pipeline.yml
 * from the repository.</p>
 *
 * <p>Field aliases support both the legacy manual trigger format and the
 * control plane DAG dispatch format (JobDispatchMessage).</p>
 *
 * @param jobId              unique id of this execution (used for idempotency).
 * @param runId              pipeline run id for run-level workspace isolation.
 * @param pipelineId         logical pipeline identifier the job belongs to.
 * @param pipelineVersionId  immutable pipeline version id from control plane.
 * @param jobName            name of the job to execute.
 * @param jobType            type of the job (BUILD, TEST, SCAN, DEPLOY, PACKAGE, CUSTOM).
 * @param repositoryUrl      git repository URL to clone for source code.
 * @param commitSha          exact commit that must be built (never "latest").
 * @param branch             branch the commit belongs to (informational).
 * @param pipelineFile       path of the pipeline YAML inside the repository (legacy).
 * @param environment        trusted environment variables passed to every step.
 * @param metadata           extension point for future job fields.
 * @param createdAt          when the job was created by the backend.
 * @param attemptNumber      attempt number for retries.
 * @param steps              declared steps from the control plane'"'"'s PipelineVersion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineJob(
        @JsonProperty("jobId") @JsonAlias("jobId") String jobId,
        @JsonProperty("runId") @JsonAlias("runId") String runId,
        @JsonProperty("pipelineId") @JsonAlias("pipelineId") String pipelineId,
        @JsonProperty("pipelineVersionId") @JsonAlias("pipelineVersionId") String pipelineVersionId,
        @JsonProperty("jobName") @JsonAlias("jobName") String jobName,
        @JsonProperty("jobType") @JsonAlias("jobType") String jobType,
        @JsonProperty("repositoryUrl") @JsonAlias({"repositoryUrl", "gitUrl"}) String repositoryUrl,
        @JsonProperty("commitSha") @JsonAlias("commitSha") String commitSha,
        @JsonProperty("branch") @JsonAlias("branch") String branch,
        @JsonProperty("pipelineFile") @JsonAlias("pipelineFile") String pipelineFile,
        @JsonProperty("environment") @JsonAlias("environment") Map<String, String> environment,
        @JsonProperty("metadata") @JsonAlias("metadata") Map<String, Object> metadata,
        @JsonProperty("createdAt") @JsonAlias("createdAt") Instant createdAt,
        @JsonProperty("attemptNumber") @JsonAlias("attemptNumber") Integer attemptNumber,
        @JsonProperty("steps") @JsonAlias("steps") List<WorkerStepDefinition> steps) {

    public PipelineJob {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * Legacy constructor for backward compatibility with manual trigger format.
     */
    public PipelineJob(String jobId, String pipelineId, String repositoryUrl,
                       String commitSha, String branch, String pipelineFile,
                       Map<String, String> environment, Map<String, Object> metadata,
                       Instant createdAt) {
        this(jobId, null, pipelineId, null, null, null, repositoryUrl, commitSha, branch,
                pipelineFile, environment, metadata, createdAt, null, List.of());
    }

    /**
     * Legacy constructor with runId for run-level workspace isolation.
     */
    public PipelineJob(String jobId, String runId, String pipelineId,
                       String repositoryUrl, String commitSha, String branch,
                       String pipelineFile, Map<String, String> environment,
                       Map<String, Object> metadata, Instant createdAt) {
        this(jobId, runId, pipelineId, null, null, null, repositoryUrl, commitSha, branch,
                pipelineFile, environment, metadata, createdAt, null, List.of());
    }

    /**
     * Constructor for control plane dispatch format (with runId but no pipelineId).
     */
    public PipelineJob(String jobId, String runId, String pipelineVersionId,
                       String jobName, String jobType, String repositoryUrl,
                       String commitSha, String branch, Integer attemptNumber,
                       List<WorkerStepDefinition> steps,
                       Map<String, String> environment, Map<String, Object> metadata,
                       Instant createdAt) {
        this(jobId, runId, null, pipelineVersionId, jobName, jobType, repositoryUrl,
                commitSha, branch, null, environment, metadata, createdAt, attemptNumber, steps);
    }

    /**
     * Checks if this job has declared steps from the control plane.
     */
    public boolean hasDeclaredSteps() {
        return steps != null && !steps.isEmpty();
    }

    /**
     * Step definition compatible with control plane'"'"'s StepDefinition (name + run command).
     */
    public record WorkerStepDefinition(
            @JsonProperty("name") @JsonAlias("name") String name,
            @JsonProperty("run") @JsonAlias("run") String run) {
        public WorkerStepDefinition {
            // Allow null name, will be derived from run command if needed
        }
    }
}
