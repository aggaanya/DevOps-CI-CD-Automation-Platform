package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {
    List<PipelineRun> findByPipelineVersionIdOrderByCreatedAtDesc(UUID pipelineVersionId);
    List<PipelineRun> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
    List<PipelineRun> findByCommitSha(String commitSha);
}
