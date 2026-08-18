package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateRepositoryRequest(
        @NotNull(message = "Project ID is required")
        UUID projectId,

        @NotBlank(message = "Provider is required")
        String provider,

        @NotBlank(message = "Repository URL is required")
        @Size(max = 1024, message = "Repository URL must not exceed 1024 characters")
        String repositoryUrl,

        @NotBlank(message = "Repository name is required")
        @Size(max = 255, message = "Repository name must not exceed 255 characters")
        String repositoryName,

        @Size(max = 255, message = "Default branch must not exceed 255 characters")
        String defaultBranch
) {}
