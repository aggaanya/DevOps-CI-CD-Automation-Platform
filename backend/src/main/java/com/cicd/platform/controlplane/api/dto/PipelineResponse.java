package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.Pipeline;
import java.time.Instant;
import java.util.UUID;

public record PipelineResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PipelineResponse from(Pipeline pipeline) {
        return new PipelineResponse(
                pipeline.getId(),
                pipeline.getProject().getId(),
                pipeline.getName(),
                pipeline.getDescription(),
                pipeline.getStatus().name(),
                pipeline.getCreatedAt(),
                pipeline.getUpdatedAt()
        );
    }
}
