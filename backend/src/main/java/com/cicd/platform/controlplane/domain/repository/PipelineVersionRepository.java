package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineVersionRepository extends JpaRepository<PipelineVersion, UUID> {
    List<PipelineVersion> findByPipelineIdOrderByVersionDesc(UUID pipelineId);
    Optional<PipelineVersion> findByPipelineIdAndVersion(UUID pipelineId, Integer version);

    @Query("SELECT pv.id FROM PipelineVersion pv " +
           "JOIN pv.pipeline p " +
           "WHERE p.project.id = (" +
           "  SELECT r.project.id FROM com.cicd.platform.controlplane.domain.entity.Repository r WHERE r.id = :repositoryId" +
           ") " +
           "AND p.status = 'ACTIVE' " +
           "ORDER BY pv.version DESC")
    List<UUID> findLatestVersionIdsForRepository(@Param("repositoryId") UUID repositoryId);
}
