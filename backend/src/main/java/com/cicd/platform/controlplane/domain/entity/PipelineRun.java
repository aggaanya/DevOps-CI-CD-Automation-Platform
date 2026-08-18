package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pipeline_runs", indexes = {
        @Index(name = "idx_pipeline_runs_pipeline_version_id", columnList = "pipeline_version_id"),
        @Index(name = "idx_pipeline_runs_repository_id", columnList = "repository_id"),
        @Index(name = "idx_pipeline_runs_status", columnList = "status"),
        @Index(name = "idx_pipeline_runs_commit_sha", columnList = "commit_sha"),
        @Index(name = "idx_pipeline_runs_created_at", columnList = "created_at DESC")
})
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_version_id", nullable = false, foreignKey = @ForeignKey(name = "fk_pipeline_runs_version"))
    private PipelineVersion pipelineVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", foreignKey = @ForeignKey(name = "fk_pipeline_runs_repository"))
    private Repository repository;

    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(nullable = false, length = 255)
    private String branch;

    @Column(name = "trigger_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private TriggerType triggerType;

    @Column(name = "triggered_by", length = 255)
    private String triggeredBy;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RunStatus status = RunStatus.QUEUED;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public PipelineRun() {}

    public PipelineRun(PipelineVersion pipelineVersion, Repository repository,
                       String commitSha, String branch, TriggerType triggerType, String triggeredBy) {
        this.pipelineVersion = pipelineVersion;
        this.repository = repository;
        this.commitSha = commitSha;
        this.branch = branch;
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
    }

    public UUID getId() { return id; }

    public PipelineVersion getPipelineVersion() { return pipelineVersion; }
    public void setPipelineVersion(PipelineVersion pipelineVersion) { this.pipelineVersion = pipelineVersion; }

    public Repository getRepository() { return repository; }
    public void setRepository(Repository repository) { this.repository = repository; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public TriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(TriggerType triggerType) { this.triggerType = triggerType; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineRun that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public enum TriggerType {
        MANUAL, WEBHOOK, SCHEDULED, API
    }

    public enum RunStatus {
        QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED
    }
}
