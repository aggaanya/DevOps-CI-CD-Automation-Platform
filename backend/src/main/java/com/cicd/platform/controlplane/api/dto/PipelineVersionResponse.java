package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import java.time.Instant;
import java.util.UUID;

public record PipelineVersionResponse(
        UUID id,
        UUID pipelineId,
        Integer version,
        String commitSha,
        String createdBy,
        Instant createdAt
) {
    public static PipelineVersionResponse from(PipelineVersion pv) {
        return new PipelineVersionResponse(
                pv.getId(),
                pv.getPipeline().getId(),
                pv.getVersion(),
                pv.getCommitSha(),
                pv.getCreatedBy(),
                pv.getCreatedAt()
        );
    }
}
