package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "worker_results", indexes = {
        @Index(name = "idx_worker_results_job_id", columnList = "job_id"),
        @Index(name = "idx_worker_results_status", columnList = "status"),
        @Index(name = "idx_worker_results_received_at", columnList = "received_at DESC")
})
public class WorkerResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false, length = 255)
    private String jobId;

    @Column(name = "pipeline_id", length = 255)
    private String pipelineId;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "worker_id", nullable = false, length = 255)
    private String workerId;

    @Column(name = "repository_url", length = 1024)
    private String repositoryUrl;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(length = 255)
    private String branch;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(length = 2000)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    public WorkerResult() {}

    public WorkerResult(String jobId, String pipelineId, String status, String workerId,
                        String repositoryUrl, String commitSha, String branch,
                        Instant startedAt, Instant completedAt, Long durationMs,
                        String message, String payload) {
        this.jobId = jobId;
        this.pipelineId = pipelineId;
        this.status = status;
        this.workerId = workerId;
        this.repositoryUrl = repositoryUrl;
        this.commitSha = commitSha;
        this.branch = branch;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.message = message;
        this.payload = payload;
    }

    @PrePersist
    protected void onCreate() {
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getReceivedAt() { return receivedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkerResult that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}