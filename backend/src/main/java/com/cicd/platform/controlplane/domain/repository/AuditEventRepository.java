package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, UUID resourceId);
    List<AuditEvent> findByCorrelationId(String correlationId);
}
