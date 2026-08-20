package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateRepositoryRequest(
        @Size(max = 1024, message = "Repository URL must not exceed 1024 characters")
        String repositoryUrl,

        @Size(max = 255, message = "Repository name must not exceed 255 characters")
        String repositoryName,

        @Size(max = 255, message = "Default branch must not exceed 255 characters")
        String defaultBranch,

        @Size(max = 50, message = "Status must not exceed 50 characters")
        String status
) {}
