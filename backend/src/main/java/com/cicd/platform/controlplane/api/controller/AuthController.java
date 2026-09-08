package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.AuthMeResponse;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.entity.ProjectMembership;
import com.cicd.platform.controlplane.security.AuthUserService;
import com.cicd.platform.controlplane.security.CurrentUser;
import com.cicd.platform.controlplane.security.ProjectAccessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final ProjectAccessService projectAccessService;
    private final AuthUserService authUserService;

    public AuthController(ProjectAccessService projectAccessService, AuthUserService authUserService) {
        this.projectAccessService = projectAccessService;
        this.authUserService = authUserService;
    }

    @GetMapping("/me")
    public ResponseEntity<AuthMeResponse> me() {
        CurrentUser user = projectAccessService.currentUser();
        authUserService.findOrCreate(user);

        List<AuthMeResponse.ProjectAccess> projects = new ArrayList<>();
        if (user.isAdmin()) {
            for (Project project : projectAccessService.findAccessibleProjects()) {
                projects.add(new AuthMeResponse.ProjectAccess(
                        project.getId().toString(), project.getName(), project.getSlug(), "ADMIN"));
            }
        } else {
            for (ProjectMembership membership : projectAccessService.membershipsOfCurrent()) {
                Project project = membership.getProject();
                projects.add(new AuthMeResponse.ProjectAccess(
                        project.getId().toString(), project.getName(), project.getSlug(), membership.getRole().name()));
            }
        }
        return ResponseEntity.ok(new AuthMeResponse(
                user.subject(), user.username(), user.email(), user.displayName(),
                user.roles().stream().map(Enum::name).toList(), projects));
    }
}