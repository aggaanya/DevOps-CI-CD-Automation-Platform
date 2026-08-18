package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "artifacts", indexes = {
        @Index(name = "idx_artifacts_pipeline_run_id", columnList = "pipeline_run_id")
})
public class Artifact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false, foreignKey = @ForeignKey(name = "fk_artifacts_run"))
    private PipelineRun pipelineRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", foreignKey = @ForeignKey(name = "fk_artifacts_job"))
    private PipelineJob job;

    @Column(name = "artifact_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ArtifactType artifactType;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "location_url", nullable = false, length = 1024)
    private String locationUrl;

    @Column(name = "image_digest", length = 255)
    private String imageDigest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Artifact() {}

    public Artifact(PipelineRun pipelineRun, ArtifactType artifactType, String name, String locationUrl) {
        this.pipelineRun = pipelineRun;
        this.artifactType = artifactType;
        this.name = name;
        this.locationUrl = locationUrl;
    }

    public UUID getId() { return id; }

    public PipelineRun getPipelineRun() { return pipelineRun; }
    public void setPipelineRun(PipelineRun pipelineRun) { this.pipelineRun = pipelineRun; }

    public PipelineJob getJob() { return job; }
    public void setJob(PipelineJob job) { this.job = job; }

    public ArtifactType getArtifactType() { return artifactType; }
    public void setArtifactType(ArtifactType artifactType) { this.artifactType = artifactType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLocationUrl() { return locationUrl; }
    public void setLocationUrl(String locationUrl) { this.locationUrl = locationUrl; }

    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Artifact that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public enum ArtifactType {
        DOCKER_IMAGE, MAVEN_JAR, NPM_PACKAGE, GENERIC
    }
}
