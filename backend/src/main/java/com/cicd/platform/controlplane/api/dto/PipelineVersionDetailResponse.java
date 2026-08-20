package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import java.time.Instant;
import java.util.UUID;

public record PipelineVersionDetailResponse(
        UUID id,
        UUID pipelineId,
        Integer version,
        String yamlContent,
        String commitSha,
        String createdBy,
        Instant createdAt
) {
    public static PipelineVersionDetailResponse from(PipelineVersion pv) {
        return new PipelineVersionDetailResponse(
                pv.getId(),
                pv.getPipeline().getId(),
                pv.getVersion(),
                pv.getYamlContent(),
                pv.getCommitSha(),
                pv.getCreatedBy(),
                pv.getCreatedAt()
        );
    }
}
