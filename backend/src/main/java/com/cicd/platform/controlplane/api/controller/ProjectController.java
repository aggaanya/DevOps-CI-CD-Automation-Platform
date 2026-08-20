package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.CreateProjectRequest;
import com.cicd.platform.controlplane.api.dto.ProjectResponse;
import com.cicd.platform.controlplane.api.dto.UpdateProjectRequest;
import com.cicd.platform.controlplane.domain.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        var project = projectService.create(
                request.organizationId(), request.name(), request.slug(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable UUID id) {
        var project = projectService.findById(id);
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(@RequestParam UUID organizationId) {
        var projects = projectService.findByOrganizationId(organizationId);
        return ResponseEntity.ok(projects.stream().map(ProjectResponse::from).toList());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateProjectRequest request) {
        var project = projectService.update(id, request.name(), request.description());
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
