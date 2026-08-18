package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineVersionRepository extends JpaRepository<PipelineVersion, UUID> {
    List<PipelineVersion> findByPipelineIdOrderByVersionDesc(UUID pipelineId);
    Optional<PipelineVersion> findByPipelineIdAndVersion(UUID pipelineId, Integer version);
}
