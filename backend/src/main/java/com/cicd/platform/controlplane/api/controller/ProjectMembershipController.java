package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.GrantMembershipRequest;
import com.cicd.platform.controlplane.api.dto.MembershipResponse;
import com.cicd.platform.controlplane.api.dto.UpdateMembershipRequest;
import com.cicd.platform.controlplane.domain.entity.ProjectMembership;
import com.cicd.platform.controlplane.security.ProjectAccessService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
public class ProjectMembershipController {

    private final ProjectAccessService projectAccessService;

    public ProjectMembershipController(ProjectAccessService projectAccessService) {
        this.projectAccessService = projectAccessService;
    }

    @GetMapping
    public ResponseEntity<List<MembershipResponse>> list(@PathVariable UUID projectId) {
        List<MembershipResponse> members = projectAccessService.membershipsOfProject(projectId).stream()
                .map(MembershipResponse::from)
                .toList();
        return ResponseEntity.ok(members);
    }

    @PostMapping
    public ResponseEntity<MembershipResponse> grant(@PathVariable UUID projectId,
                                                    @Valid @RequestBody GrantMembershipRequest request) {
        ProjectMembership membership = projectAccessService.grantMembership(projectId, request.keycloakSubject(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(MembershipResponse.from(membership));
    }

    @PutMapping("/{membershipId}")
    public ResponseEntity<MembershipResponse> update(@PathVariable UUID projectId,
                                                     @PathVariable UUID membershipId,
                                                     @Valid @RequestBody UpdateMembershipRequest request) {
        ProjectMembership membership = projectAccessService.updateMembershipRole(membershipId, request.role());
        return ResponseEntity.ok(MembershipResponse.from(membership));
    }

    @DeleteMapping("/{membershipId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID projectId, @PathVariable UUID membershipId) {
        projectAccessService.revokeMembership(membershipId);
        return ResponseEntity.noContent().build();
    }
}