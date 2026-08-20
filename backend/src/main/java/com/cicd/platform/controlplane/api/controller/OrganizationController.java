package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.CreateOrganizationRequest;
import com.cicd.platform.controlplane.api.dto.OrganizationResponse;
import com.cicd.platform.controlplane.api.dto.UpdateOrganizationRequest;
import com.cicd.platform.controlplane.domain.service.OrganizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        var org = organizationService.create(request.name(), request.slug(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrganizationResponse.from(org));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationResponse> getById(@PathVariable UUID id) {
        var org = organizationService.findById(id);
        return ResponseEntity.ok(OrganizationResponse.from(org));
    }

    @GetMapping
    public ResponseEntity<List<OrganizationResponse>> list() {
        var orgs = organizationService.findAll();
        return ResponseEntity.ok(orgs.stream().map(OrganizationResponse::from).toList());
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrganizationResponse> update(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateOrganizationRequest request) {
        var org = organizationService.update(id, request.name(), request.description());
        return ResponseEntity.ok(OrganizationResponse.from(org));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        organizationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
