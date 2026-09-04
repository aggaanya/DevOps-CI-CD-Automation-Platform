package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.Repository;
import java.time.Instant;
import java.util.UUID;

public record RepositoryResponse(
        UUID id,
        UUID projectId,
        String provider,
        String repositoryUrl,
        String cloneUrl,
        String repositoryName,
        String defaultBranch,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static RepositoryResponse from(Repository repo) {
        return new RepositoryResponse(
                repo.getId(),
                repo.getProject().getId(),
                repo.getProvider().name(),
                repo.getRepositoryUrl(),
                repo.getCloneUrl(),
                repo.getRepositoryName(),
                repo.getDefaultBranch(),
                repo.getStatus().name(),
                repo.getCreatedAt(),
                repo.getUpdatedAt()
        );
    }
}
