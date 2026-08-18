package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "job_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_job_attempts_job_attempt", columnNames = {"job_id", "attempt_number"})
}, indexes = {
        @Index(name = "idx_job_attempts_job_id", columnList = "job_id")
})
public class JobAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, foreignKey = @ForeignKey(name = "fk_job_attempts_job"))
    private PipelineJob job;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private AttemptStatus status = AttemptStatus.PENDING;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "logs_location", length = 1024)
    private String logsLocation;

    @PrePersist
    protected void onCreate() {}

    public JobAttempt() {}

    public JobAttempt(PipelineJob job, Integer attemptNumber) {
        this.job = job;
        this.attemptNumber = attemptNumber;
    }

    public UUID getId() { return id; }

    public PipelineJob getJob() { return job; }
    public void setJob(PipelineJob job) { this.job = job; }

    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }

    public AttemptStatus getStatus() { return status; }
    public void setStatus(AttemptStatus status) { this.status = status; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getLogsLocation() { return logsLocation; }
    public void setLogsLocation(String logsLocation) { this.logsLocation = logsLocation; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JobAttempt that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public enum AttemptStatus {
        PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
    }
}
