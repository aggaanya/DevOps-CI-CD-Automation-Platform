package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TriggerPipelineRunRequest(
        @NotNull(message = "Pipeline version ID is required")
        UUID pipelineVersionId,

        @NotBlank(message = "Commit SHA is required")
        @Pattern(regexp = "^[A-Za-z0-9._\\-]+$", message = "Commit SHA contains invalid characters")
        @Size(max = 255, message = "Commit SHA is too long")
        String commitSha,

        @NotBlank(message = "Branch is required")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._\\-/]*$", message = "Branch contains invalid characters")
        String branch,

        UUID repositoryId,

        String triggeredBy
) {}
