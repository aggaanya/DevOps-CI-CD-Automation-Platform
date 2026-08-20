package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.Deployment;
import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
        UUID id,
        UUID pipelineRunId,
        String environment,
        String imageDigest,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String endpoint,
        Instant createdAt
) {
    public static DeploymentResponse from(Deployment deployment) {
        return new DeploymentResponse(
                deployment.getId(),
                deployment.getPipelineRun().getId(),
                deployment.getEnvironment(),
                deployment.getImageDigest(),
                deployment.getStatus().name(),
                deployment.getStartedAt(),
                deployment.getFinishedAt(),
                deployment.getEndpoint(),
                deployment.getCreatedAt()
        );
    }
}
