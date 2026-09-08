package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.ProjectMembership;

import java.time.Instant;
import java.util.UUID;

public record MembershipResponse(
        UUID id,
        UUID projectId,
        String projectName,
        String keycloakSubject,
        String username,
        String role,
        Instant createdAt,
        Instant updatedAt
) {
    public static MembershipResponse from(ProjectMembership membership) {
        return new MembershipResponse(
                membership.getId(),
                membership.getProject() != null ? membership.getProject().getId() : null,
                membership.getProject() != null ? membership.getProject().getName() : null,
                membership.getUser() != null ? membership.getUser().getKeycloakSubject() : null,
                membership.getUser() != null ? membership.getUser().getUsername() : null,
                membership.getRole() != null ? membership.getRole().name() : null,
                membership.getCreatedAt(),
                membership.getUpdatedAt());
    }
}