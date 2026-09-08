package com.cicd.platform.controlplane.api.dto;

import java.util.List;

public record AuthMeResponse(
        String subject,
        String username,
        String email,
        String displayName,
        List<String> roles,
        List<ProjectAccess> projects
) {
    public record ProjectAccess(String projectId, String name, String slug, String role) {
    }
}