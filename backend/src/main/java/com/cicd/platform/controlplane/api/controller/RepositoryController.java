package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.CreateRepositoryRequest;
import com.cicd.platform.controlplane.api.dto.RepositoryResponse;
import com.cicd.platform.controlplane.api.dto.RunResponse;
import com.cicd.platform.controlplane.api.dto.UpdateRepositoryRequest;
import com.cicd.platform.controlplane.domain.entity.Repository;
import com.cicd.platform.controlplane.domain.service.RepositoryService;
import com.cicd.platform.controlplane.execution.RunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final RunService runService;

    public RepositoryController(RepositoryService repositoryService, RunService runService) {
        this.repositoryService = repositoryService;
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RepositoryResponse> create(@Valid @RequestBody CreateRepositoryRequest request) {
        var repo = repositoryService.create(
                request.projectId(), request.provider(), request.repositoryUrl(),
                request.repositoryName(), request.defaultBranch());
        return ResponseEntity.status(HttpStatus.CREATED).body(RepositoryResponse.from(repo));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepositoryResponse> getById(@PathVariable UUID id) {
        var repo = repositoryService.findById(id);
        return ResponseEntity.ok(RepositoryResponse.from(repo));
    }

    @GetMapping
    public ResponseEntity<List<RepositoryResponse>> list(@RequestParam UUID projectId) {
        var repos = repositoryService.findByProjectId(projectId);
        return ResponseEntity.ok(repos.stream().map(RepositoryResponse::from).toList());
    }

    @PutMapping("/{id}")
    public ResponseEntity<RepositoryResponse> update(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateRepositoryRequest request) {
        Repository.RepositoryStatus status = null;
        if (request.status() != null) {
            status = Repository.RepositoryStatus.valueOf(request.status());
        }
        var repo = repositoryService.update(id, request.repositoryUrl(), request.repositoryName(),
                request.defaultBranch(), status);
        return ResponseEntity.ok(RepositoryResponse.from(repo));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repositoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<RunResponse>> getRuns(@PathVariable UUID id) {
        repositoryService.findById(id);
        var runs = runService.getRunsByRepositoryId(id);
        return ResponseEntity.ok(runs.stream().map(RunResponse::from).toList());
    }
}
