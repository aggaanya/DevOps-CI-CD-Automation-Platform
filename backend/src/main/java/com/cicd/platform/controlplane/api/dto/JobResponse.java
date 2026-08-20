package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        UUID stageId,
        String name,
        String jobType,
        String status,
        String workerId,
        Integer exitCode,
        Instant startedAt,
        Instant finishedAt
) {
    public static JobResponse from(PipelineJob job) {
        return new JobResponse(
                job.getId(),
                job.getPipelineStage().getId(),
                job.getName(),
                job.getJobType().name(),
                job.getStatus().name(),
                job.getWorkerId(),
                job.getExitCode(),
                job.getStartedAt(),
                job.getFinishedAt()
        );
    }
}
