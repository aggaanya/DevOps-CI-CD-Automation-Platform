package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pipeline_jobs", indexes = {
        @Index(name = "idx_pipeline_jobs_pipeline_stage_id", columnList = "pipeline_stage_id"),
        @Index(name = "idx_pipeline_jobs_status", columnList = "status")
})
public class PipelineJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_stage_id", nullable = false, foreignKey = @ForeignKey(name = "fk_pipeline_jobs_stage"))
    private PipelineStage pipelineStage;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "job_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private JobType jobType;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "worker_id", length = 255)
    private String workerId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    @PrePersist
    protected void onCreate() {}

    public PipelineJob() {}

    public PipelineJob(PipelineStage pipelineStage, String name, JobType jobType) {
        this.pipelineStage = pipelineStage;
        this.name = name;
        this.jobType = jobType;
    }

    public UUID getId() { return id; }

    public PipelineStage getPipelineStage() { return pipelineStage; }
    public void setPipelineStage(PipelineStage pipelineStage) { this.pipelineStage = pipelineStage; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public JobType getJobType() { return jobType; }
    public void setJobType(JobType jobType) { this.jobType = jobType; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineJob that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public enum JobType {
        BUILD, TEST, SCAN, DEPLOY, PACKAGE, CUSTOM
    }

    public enum JobStatus {
        PENDING, QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED
    }
}
