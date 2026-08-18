package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    List<Repository> findByProjectId(UUID projectId);
}
