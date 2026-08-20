package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.domain.entity.AuditEvent;
import com.cicd.platform.controlplane.domain.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public AuditEvent record(String actor, String action, String resourceType, UUID resourceId) {
        return record(actor, action, resourceType, resourceId, null, null);
    }

    public AuditEvent record(String actor, String action, String resourceType, UUID resourceId,
                              Map<String, Object> metadata, String correlationId) {
        AuditEvent event = new AuditEvent(actor, action, resourceType, resourceId);
        event.setMetadata(metadata);
        event.setCorrelationId(correlationId);
        event = auditEventRepository.save(event);
        log.debug("Audit event recorded: actor={}, action={}, resource={}:{}", actor, action, resourceType, resourceId);
        return event;
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByResource(String resourceType, UUID resourceId) {
        return auditEventRepository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(resourceType, resourceId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByCorrelationId(String correlationId) {
        return auditEventRepository.findByCorrelationId(correlationId);
    }
}
