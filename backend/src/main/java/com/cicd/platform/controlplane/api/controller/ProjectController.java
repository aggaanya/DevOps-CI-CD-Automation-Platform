package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.CreateProjectRequest;
import com.cicd.platform.controlplane.api.dto.ProjectResponse;
import com.cicd.platform.controlplane.api.dto.UpdateProjectRequest;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.service.ProjectService;
import com.cicd.platform.controlplane.security.ProjectAccessService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@Schema(description = "Projects; creation requires the global ADMIN role, reads are scoped to the caller's membership")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectAccessService projectAccessService;

    public ProjectController(ProjectService projectService, ProjectAccessService projectAccessService) {
        this.projectService = projectService;
        this.projectAccessService = projectAccessService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        Project project = projectService.create(
                request.organizationId(), request.name(), request.slug(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable UUID id) {
        projectAccessService.assertProjectRead(id);
        Project project = projectService.findById(id);
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(@RequestParam UUID organizationId) {
        List<Project> projects = projectAccessService.findAccessibleProjects(organizationId);
        return ResponseEntity.ok(projects.stream().map(ProjectResponse::from).toList());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateProjectRequest request) {
        projectAccessService.assertProjectAdmin(id);
        Project project = projectService.update(id, request.name(), request.description());
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        projectAccessService.assertProjectAdmin(id);
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}