package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateMembershipRequest(
        @NotBlank(message = "role is required") String role
) {
}