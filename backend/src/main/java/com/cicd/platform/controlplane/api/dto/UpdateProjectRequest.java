package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @Size(max = 255, message = "Name must not exceed 255 characters")
        String name,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description
) {}
