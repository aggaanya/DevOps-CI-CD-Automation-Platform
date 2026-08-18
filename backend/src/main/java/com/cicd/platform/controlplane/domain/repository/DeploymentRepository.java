package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {
    List<Deployment> findByPipelineRunId(UUID pipelineRunId);
    List<Deployment> findByEnvironmentOrderByCreatedAtDesc(String environment);
}
