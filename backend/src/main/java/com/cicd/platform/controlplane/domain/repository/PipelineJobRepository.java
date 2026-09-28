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

    /**
     * Loads every job in {@code status} together with its stage and run. The
     * recovery sweep needs the run id and run status to decide whether a QUEUED
     * job's lost message should be republished, and reads happen outside any
     * transaction, so the graph must arrive in a single query rather than through
     * lazy association loading.
     */
    @Query("select j from PipelineJob j join fetch j.pipelineStage stage "
            + "join fetch stage.pipelineRun run where j.status = :status")
    List<PipelineJob> findByStatusWithRun(@Param("status") PipelineJob.JobStatus status);

    @Transactional
    @Modifying
    @Query("update PipelineJob j set j.status = :newStatus, j.workerId = :workerId, j.startedAt = :startedAt "
            + "where j.id = :jobId and j.status = :expectedStatus")
    int transitionStatus(@Param("jobId") UUID jobId,
                         @Param("expectedStatus") PipelineJob.JobStatus expectedStatus,
                         @Param("newStatus") PipelineJob.JobStatus newStatus,
                         @Param("workerId") String workerId,
                         @Param("startedAt") Instant startedAt);

    /**
     * Claims a job for dispatch with a single conditional UPDATE.
     *
     * <p>This is the scheduler's duplicate-execution guard. Two scheduler threads
     * (for example the HTTP trigger and a job-completion callback) may both
     * observe the same job as runnable; exactly one of them can move it out of
     * {@code PENDING}, and only that one publishes a dispatch message. The
     * {@code in} clause is what allows the retry path, which legitimately
     * re-queues a job from {@code FAILED}, to share the same code path.
     *
     * <p>{@code flushAutomatically} persists everything staged earlier in the
     * transaction before the UPDATE runs, and {@code clearAutomatically} drops
     * the now-stale first-level cache so subsequent reads observe the new status
     * rather than the value the scheduler decided on.
     *
     * @return 1 when this caller won the claim, 0 when another caller already had
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PipelineJob j set j.status = :newStatus "
            + "where j.id = :jobId and j.status in :expectedStatuses")
    int claimForDispatch(@Param("jobId") UUID jobId,
                         @Param("expectedStatuses") List<PipelineJob.JobStatus> expectedStatuses,
                         @Param("newStatus") PipelineJob.JobStatus newStatus);

    /**
     * Marks a job as SKIPPED because an upstream dependency can no longer
     * succeed. Conditional on {@code PENDING} so concurrent scheduler passes
     * cannot double-skip, and terminal so a job that somehow started anyway is
     * never silently rewritten.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PipelineJob j set j.status = :newStatus, j.finishedAt = :finishedAt "
            + "where j.id = :jobId and j.status = :expectedStatus")
    int markTerminal(@Param("jobId") UUID jobId,
                     @Param("expectedStatus") PipelineJob.JobStatus expectedStatus,
                     @Param("newStatus") PipelineJob.JobStatus newStatus,
                     @Param("finishedAt") Instant finishedAt);

    /**
     * Moves a terminal job back to PENDING so the scheduler can claim it again.
     * Used by the retry path, which re-queues a FAILED job as a fresh attempt.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PipelineJob j set j.status = :newStatus, j.finishedAt = null "
            + "where j.id = :jobId and j.status in :expectedStatuses")
    int releaseForRetry(@Param("jobId") UUID jobId,
                        @Param("expectedStatuses") List<PipelineJob.JobStatus> expectedStatuses,
                        @Param("newStatus") PipelineJob.JobStatus newStatus);
}
