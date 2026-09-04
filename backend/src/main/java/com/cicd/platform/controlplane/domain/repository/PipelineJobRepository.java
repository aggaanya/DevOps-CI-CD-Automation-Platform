package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PipelineJobRepository extends JpaRepository<PipelineJob, UUID> {
    List<PipelineJob> findByPipelineStageId(UUID pipelineStageId);

    @Transactional
    @Modifying
    @Query("update PipelineJob j set j.status = :newStatus, j.workerId = :workerId, j.startedAt = :startedAt "
            + "where j.id = :jobId and j.status = :expectedStatus")
    int transitionStatus(@Param("jobId") UUID jobId,
                         @Param("expectedStatus") PipelineJob.JobStatus expectedStatus,
                         @Param("newStatus") PipelineJob.JobStatus newStatus,
                         @Param("workerId") String workerId,
                         @Param("startedAt") Instant startedAt);
}