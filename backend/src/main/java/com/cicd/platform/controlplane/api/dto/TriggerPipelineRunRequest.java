package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TriggerPipelineRunRequest(
        @NotNull(message = "Pipeline version ID is required")
        UUID pipelineVersionId,

        @NotBlank(message = "Commit SHA is required")
        String commitSha,

        @NotBlank(message = "Branch is required")
        String branch,

        UUID repositoryId,

        String triggeredBy
) {}
