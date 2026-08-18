package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {
    List<JobAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);
}
