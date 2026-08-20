package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.Size;

public record UpdatePipelineRequest(
        @Size(max = 255, message = "Name must not exceed 255 characters")
        String name,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        @Size(max = 50, message = "Status must not exceed 50 characters")
        String status
) {}
