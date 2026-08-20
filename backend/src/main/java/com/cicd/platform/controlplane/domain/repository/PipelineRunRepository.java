package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {
    List<PipelineRun> findByPipelineVersionIdOrderByCreatedAtDesc(UUID pipelineVersionId);
    List<PipelineRun> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
    List<PipelineRun> findByCommitSha(String commitSha);

    @Query("SELECT r FROM PipelineRun r JOIN r.pipelineVersion pv WHERE pv.pipeline.id = :pipelineId ORDER BY r.createdAt DESC")
    List<PipelineRun> findByPipelineIdOrderByCreatedAtDesc(@Param("pipelineId") UUID pipelineId);
}
