package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.*;
import com.cicd.platform.controlplane.domain.service.PipelineService;
import com.cicd.platform.controlplane.pipeline.PipelineYamlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipelineYamlService pipelineYamlService;

    public PipelineController(PipelineService pipelineService, PipelineYamlService pipelineYamlService) {
        this.pipelineService = pipelineService;
        this.pipelineYamlService = pipelineYamlService;
    }

    @PostMapping
    public ResponseEntity<PipelineResponse> create(@Valid @RequestBody CreatePipelineRequest request) {
        var pipeline = pipelineService.create(request.projectId(), request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(PipelineResponse.from(pipeline));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PipelineResponse> getById(@PathVariable UUID id) {
        var pipeline = pipelineService.findById(id);
        return ResponseEntity.ok(PipelineResponse.from(pipeline));
    }

    @GetMapping
    public ResponseEntity<List<PipelineResponse>> list(@RequestParam UUID projectId) {
        var pipelines = pipelineService.findByProjectId(projectId);
        return ResponseEntity.ok(pipelines.stream().map(PipelineResponse::from).toList());
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<PipelineVersionResponse>> versions(@PathVariable UUID id) {
        var versions = pipelineService.findVersions(id);
        return ResponseEntity.ok(versions.stream().map(PipelineVersionResponse::from).toList());
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<PipelineVersionResponse> submitYaml(
            @PathVariable UUID id,
            @Valid @RequestBody SubmitPipelineYamlRequest request) {
        var version = pipelineYamlService.submitYaml(id, request.yamlContent(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(PipelineVersionResponse.from(version));
    }

    @PostMapping("/yaml")
    public ResponseEntity<PipelineVersionResponse> submitYamlToProject(
            @RequestParam UUID projectId,
            @Valid @RequestBody SubmitPipelineYamlRequest request) {
        var version = pipelineYamlService.validateAndSubmitToProject(projectId, request.yamlContent(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(PipelineVersionResponse.from(version));
    }
}
