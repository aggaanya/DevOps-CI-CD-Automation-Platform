package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PipelineStageRepository extends JpaRepository<PipelineStage, UUID> {
    List<PipelineStage> findByPipelineRunIdOrderByOrderIndexAsc(UUID pipelineRunId);

    /**
     * Terminal transition of a stage, conditional on the stage still being active.
     *
     * <p>Same duplicate-completion guard as {@code PipelineRunRepository#completeRun}:
     * when two parallel branches of the same stage finish at once, only the first
     * writer settles the stage and emits STAGE_COMPLETED.
     *
     * @return 1 when this caller transitioned the stage, 0 when it was already terminal
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PipelineStage s set s.status = :newStatus, "
            + "s.startedAt = coalesce(s.startedAt, :startedAt), s.finishedAt = :finishedAt "
            + "where s.id = :stageId and s.status in :expectedStatuses")
    int completeStage(@Param("stageId") UUID stageId,
                      @Param("expectedStatuses") List<PipelineStage.StageStatus> expectedStatuses,
                      @Param("newStatus") PipelineStage.StageStatus newStatus,
                      @Param("startedAt") Instant startedAt,
                      @Param("finishedAt") Instant finishedAt);
}
