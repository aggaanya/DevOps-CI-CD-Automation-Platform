package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.WorkerResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkerResultRepository extends JpaRepository<WorkerResult, UUID> {
    boolean existsByJobId(String jobId);
    List<WorkerResult> findByJobIdOrderByReceivedAtDesc(String jobId);
    List<WorkerResult> findTop20ByOrderByReceivedAtDesc();
}