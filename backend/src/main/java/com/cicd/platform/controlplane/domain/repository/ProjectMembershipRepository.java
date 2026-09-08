package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.ProjectMembership;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMembershipRepository extends JpaRepository<ProjectMembership, UUID> {

    @EntityGraph(attributePaths = {"project", "user"})
    Optional<ProjectMembership> findByProjectIdAndUserKeycloakSubject(UUID projectId, String keycloakSubject);

    @EntityGraph(attributePaths = {"project", "user"})
    Optional<ProjectMembership> findWithProjectAndUserById(UUID id);

    @EntityGraph(attributePaths = {"project", "user"})
    List<ProjectMembership> findByUserKeycloakSubject(String keycloakSubject);

    @EntityGraph(attributePaths = {"project", "user"})
    List<ProjectMembership> findByProjectId(UUID projectId);

    void deleteByProjectIdAndUserKeycloakSubject(UUID projectId, String keycloakSubject);

    boolean existsByProjectIdAndUserKeycloakSubject(UUID projectId, String keycloakSubject);
}