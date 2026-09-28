package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {
    List<PipelineRun> findByPipelineVersionIdOrderByCreatedAtDesc(UUID pipelineVersionId);
    List<PipelineRun> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
    List<PipelineRun> findByCommitSha(String commitSha);

    @Query("SELECT r FROM PipelineRun r JOIN r.pipelineVersion pv WHERE pv.pipeline.id = :pipelineId ORDER BY r.createdAt DESC")
    List<PipelineRun> findByPipelineIdOrderByCreatedAtDesc(@Param("pipelineId") UUID pipelineId);

    /**
     * Terminal transition of a run, conditional on the run still being active.
     *
     * <p>With parallel branches, several job-completion callbacks can conclude
     * "every stage is terminal" at the same instant. Making the completion a
     * single conditional UPDATE means exactly one of them writes the terminal
     * status and {@code finishedAt}; the losers get 0 rows and skip the
     * RUN_COMPLETED outbox event, so the run cannot be completed twice or have
     * its finish timestamp overwritten.
     *
     * @return 1 when this caller transitioned the run, 0 when it was already terminal
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PipelineRun r set r.status = :newStatus, r.finishedAt = :finishedAt "
            + "where r.id = :runId and r.status = :expectedStatus")
    int completeRun(@Param("runId") UUID runId,
                    @Param("expectedStatus") PipelineRun.RunStatus expectedStatus,
                    @Param("newStatus") PipelineRun.RunStatus newStatus,
                    @Param("finishedAt") Instant finishedAt);
}
