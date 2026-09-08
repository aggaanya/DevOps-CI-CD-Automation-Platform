package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.DeploymentResponse;
import com.cicd.platform.controlplane.domain.entity.Deployment;
import com.cicd.platform.controlplane.domain.service.DeploymentService;
import com.cicd.platform.controlplane.security.ProjectAccessService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployments")
@Schema(description = "Deployments; scoped to the caller's project membership via the owning pipeline run")
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final ProjectAccessService projectAccessService;

    public DeploymentController(DeploymentService deploymentService, ProjectAccessService projectAccessService) {
        this.deploymentService = deploymentService;
        this.projectAccessService = projectAccessService;
    }

    @PostMapping
    public ResponseEntity<DeploymentResponse> create(@RequestParam UUID pipelineRunId,
                                                     @RequestParam String environment) {
        projectAccessService.assertProjectWrite(projectAccessService.resolveProjectForRun(pipelineRunId));
        var deployment = deploymentService.create(pipelineRunId, environment);
        return ResponseEntity.status(HttpStatus.CREATED).body(DeploymentResponse.from(deployment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeploymentResponse> getById(@PathVariable UUID id) {
        projectAccessService.assertProjectRead(projectAccessService.resolveProjectForDeployment(id));
        var deployment = deploymentService.findById(id);
        return ResponseEntity.ok(DeploymentResponse.from(deployment));
    }

    @GetMapping
    public ResponseEntity<List<DeploymentResponse>> list(@RequestParam(required = false) UUID pipelineRunId,
                                                         @RequestParam(required = false) String environment) {
        if (pipelineRunId != null) {
            projectAccessService.assertProjectRead(projectAccessService.resolveProjectForRun(pipelineRunId));
        } else if (environment != null) {
            projectAccessService.assertGlobalAdmin();
        } else {
            return ResponseEntity.badRequest().build();
        }
        List<Deployment> deployments = pipelineRunId != null
                ? deploymentService.findByRunId(pipelineRunId)
                : deploymentService.findByEnvironment(environment);
        List<DeploymentResponse> responses = deployments.stream().map(DeploymentResponse::from).toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<DeploymentResponse> start(@PathVariable UUID id) {
        projectAccessService.assertProjectWrite(projectAccessService.resolveProjectForDeployment(id));
        var deployment = deploymentService.startDeployment(id);
        return ResponseEntity.ok(DeploymentResponse.from(deployment));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<DeploymentResponse> complete(@PathVariable UUID id,
                                                       @RequestParam boolean success,
                                                       @RequestParam(required = false) String endpoint) {
        projectAccessService.assertProjectWrite(projectAccessService.resolveProjectForDeployment(id));
        var deployment = deploymentService.completeDeployment(id, success, endpoint);
        return ResponseEntity.ok(DeploymentResponse.from(deployment));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        projectAccessService.assertProjectAdmin(projectAccessService.resolveProjectForDeployment(id));
        deploymentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}