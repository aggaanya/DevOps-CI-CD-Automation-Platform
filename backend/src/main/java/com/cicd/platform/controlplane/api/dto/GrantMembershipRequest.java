package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GrantMembershipRequest(
        @NotBlank(message = "keycloakSubject is required") String keycloakSubject,
        @NotBlank(message = "role is required") String role
) {
}