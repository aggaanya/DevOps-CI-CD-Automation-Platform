package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pipeline_stages", uniqueConstraints = {
        @UniqueConstraint(name = "uq_pipeline_stages_run_order", columnNames = {"pipeline_run_id", "order_index"})
}, indexes = {
        @Index(name = "idx_pipeline_stages_pipeline_run_id", columnList = "pipeline_run_id")
})
public class PipelineStage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false, foreignKey = @ForeignKey(name = "fk_pipeline_stages_run"))
    private PipelineRun pipelineRun;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private StageStatus status = StageStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    protected void onCreate() {}

    public PipelineStage() {}

    public PipelineStage(PipelineRun pipelineRun, String name, Integer orderIndex) {
        this.pipelineRun = pipelineRun;
        this.name = name;
        this.orderIndex = orderIndex;
    }

    public UUID getId() { return id; }

    public PipelineRun getPipelineRun() { return pipelineRun; }
    public void setPipelineRun(PipelineRun pipelineRun) { this.pipelineRun = pipelineRun; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getOrderIndex() { return orderIndex; }
    public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }

    public StageStatus getStatus() { return status; }
    public void setStatus(StageStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineStage that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public enum StageStatus {
        PENDING, RUNNING, SUCCESS, FAILED, SKIPPED
    }
}
