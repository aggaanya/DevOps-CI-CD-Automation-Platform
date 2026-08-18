package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {
    List<Artifact> findByPipelineRunId(UUID pipelineRunId);
}
