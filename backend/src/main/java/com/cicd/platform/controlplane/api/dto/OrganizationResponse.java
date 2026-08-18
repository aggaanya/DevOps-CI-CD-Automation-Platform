package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.Organization;
import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String slug,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrganizationResponse from(Organization org) {
        return new OrganizationResponse(
                org.getId(),
                org.getName(),
                org.getSlug(),
                org.getDescription(),
                org.getStatus().name(),
                org.getCreatedAt(),
                org.getUpdatedAt()
        );
    }
}
