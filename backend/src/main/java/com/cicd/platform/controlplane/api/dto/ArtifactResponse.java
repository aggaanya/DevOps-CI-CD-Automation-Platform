package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.Artifact;
import java.time.Instant;
import java.util.UUID;

public record ArtifactResponse(
        UUID id,
        UUID pipelineRunId,
        UUID jobId,
        String artifactType,
        String name,
        String locationUrl,
        String imageDigest,
        Instant createdAt
) {
    public static ArtifactResponse from(Artifact artifact) {
        return new ArtifactResponse(
                artifact.getId(),
                artifact.getPipelineRun().getId(),
                artifact.getJob() != null ? artifact.getJob().getId() : null,
                artifact.getArtifactType().name(),
                artifact.getName(),
                artifact.getLocationUrl(),
                artifact.getImageDigest(),
                artifact.getCreatedAt()
        );
    }
}
