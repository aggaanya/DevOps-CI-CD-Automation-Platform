package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.CreateRepositoryRequest;
import com.cicd.platform.controlplane.api.dto.RepositoryResponse;
import com.cicd.platform.controlplane.domain.service.RepositoryService;
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

    public RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
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
}
