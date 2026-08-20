package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.PipelineRun;

import java.time.Instant;
import java.util.UUID;

public record RunResponse(
        UUID id,
        UUID pipelineVersionId,
        UUID pipelineId,
        String commitSha,
        String branch,
        String triggerType,
        String triggeredBy,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
) {
    public static RunResponse from(PipelineRun run) {
        return new RunResponse(
                run.getId(),
                run.getPipelineVersion().getId(),
                run.getPipelineVersion().getPipeline().getId(),
                run.getCommitSha(),
                run.getBranch(),
                run.getTriggerType().name(),
                run.getTriggeredBy(),
                run.getStatus().name(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getCreatedAt()
        );
    }
}
