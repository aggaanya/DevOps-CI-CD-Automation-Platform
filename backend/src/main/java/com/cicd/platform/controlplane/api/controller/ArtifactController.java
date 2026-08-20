package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.ArtifactResponse;
import com.cicd.platform.controlplane.domain.entity.Artifact;
import com.cicd.platform.controlplane.domain.service.ArtifactService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @PostMapping
    public ResponseEntity<ArtifactResponse> create(@RequestParam UUID pipelineRunId,
                                                   @RequestParam String artifactType,
                                                   @RequestParam String name,
                                                   @RequestParam String locationUrl,
                                                   @RequestParam(required = false) UUID jobId) {
        Artifact.ArtifactType type = Artifact.ArtifactType.valueOf(artifactType.toUpperCase());
        var artifact = artifactService.create(pipelineRunId, type, name, locationUrl, jobId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ArtifactResponse.from(artifact));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ArtifactResponse> getById(@PathVariable UUID id) {
        var artifact = artifactService.findById(id);
        return ResponseEntity.ok(ArtifactResponse.from(artifact));
    }

    @GetMapping
    public ResponseEntity<List<ArtifactResponse>> list(@RequestParam UUID pipelineRunId) {
        var artifacts = artifactService.findByRunId(pipelineRunId);
        return ResponseEntity.ok(artifacts.stream().map(ArtifactResponse::from).toList());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        artifactService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
