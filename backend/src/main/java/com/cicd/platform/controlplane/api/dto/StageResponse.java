package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.PipelineStage;

import java.time.Instant;
import java.util.UUID;

public record StageResponse(
        UUID id,
        UUID runId,
        String name,
        Integer orderIndex,
        String status,
        Instant startedAt,
        Instant finishedAt
) {
    public static StageResponse from(PipelineStage stage) {
        return new StageResponse(
                stage.getId(),
                stage.getPipelineRun().getId(),
                stage.getName(),
                stage.getOrderIndex(),
                stage.getStatus().name(),
                stage.getStartedAt(),
                stage.getFinishedAt()
        );
    }
}
