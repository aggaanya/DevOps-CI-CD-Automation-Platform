package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pipeline_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_pipeline_versions_pipeline_version", columnNames = {"pipeline_id", "version"})
}, indexes = {
        @Index(name = "idx_pipeline_versions_pipeline_id", columnList = "pipeline_id"),
        @Index(name = "idx_pipeline_versions_commit_sha", columnList = "commit_sha")
})
public class PipelineVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_id", nullable = false, foreignKey = @ForeignKey(name = "fk_pipeline_versions_pipeline"))
    private Pipeline pipeline;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "yaml_content", nullable = false, columnDefinition = "TEXT")
    private String yamlContent;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public PipelineVersion() {}

    public PipelineVersion(Pipeline pipeline, Integer version, String yamlContent,
                           String commitSha, String createdBy) {
        this.pipeline = pipeline;
        this.version = version;
        this.yamlContent = yamlContent;
        this.commitSha = commitSha;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }

    public Pipeline getPipeline() { return pipeline; }
    public void setPipeline(Pipeline pipeline) { this.pipeline = pipeline; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getYamlContent() { return yamlContent; }
    public void setYamlContent(String yamlContent) { this.yamlContent = yamlContent; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineVersion that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
