package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    List<Pipeline> findByProjectId(UUID projectId);
}
