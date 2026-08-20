package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.DeploymentResponse;
import com.cicd.platform.controlplane.domain.service.DeploymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;

    public DeploymentController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @PostMapping
    public ResponseEntity<DeploymentResponse> create(@RequestParam UUID pipelineRunId,
                                                     @RequestParam String environment) {
        var deployment = deploymentService.create(pipelineRunId, environment);
        return ResponseEntity.status(HttpStatus.CREATED).body(DeploymentResponse.from(deployment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeploymentResponse> getById(@PathVariable UUID id) {
        var deployment = deploymentService.findById(id);
        return ResponseEntity.ok(DeploymentResponse.from(deployment));
    }

    @GetMapping
    public ResponseEntity<List<DeploymentResponse>> list(@RequestParam(required = false) UUID pipelineRunId,
                                                         @RequestParam(required = false) String environment) {
        List<?> deployments;
        if (pipelineRunId != null) {
            deployments = deploymentService.findByRunId(pipelineRunId);
        } else if (environment != null) {
            deployments = deploymentService.findByEnvironment(environment);
        } else {
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        List<DeploymentResponse> responses = ((List<? extends com.cicd.platform.controlplane.domain.entity.Deployment>) deployments)
                .stream().map(DeploymentResponse::from).toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<DeploymentResponse> start(@PathVariable UUID id) {
        var deployment = deploymentService.startDeployment(id);
        return ResponseEntity.ok(DeploymentResponse.from(deployment));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<DeploymentResponse> complete(@PathVariable UUID id,
                                                       @RequestParam boolean success,
                                                       @RequestParam(required = false) String endpoint) {
        var deployment = deploymentService.completeDeployment(id, success, endpoint);
        return ResponseEntity.ok(DeploymentResponse.from(deployment));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        deploymentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
