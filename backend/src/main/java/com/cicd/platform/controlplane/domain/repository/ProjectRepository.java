package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByOrganizationId(UUID organizationId);
    Optional<Project> findByOrganizationIdAndSlug(UUID organizationId, String slug);
    boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);
}
