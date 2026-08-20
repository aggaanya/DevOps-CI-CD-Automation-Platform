package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceConflictException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    public OrganizationService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    public Organization create(String name, String slug, String description) {
        if (organizationRepository.existsBySlug(slug)) {
            throw new ResourceConflictException("Organization with slug '" + slug + "' already exists");
        }
        Organization org = new Organization(name, slug, description);
        return organizationRepository.save(org);
    }

    @Transactional(readOnly = true)
    public Organization findById(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Organization> findAll() {
        return organizationRepository.findAll();
    }

    public Organization update(UUID id, String name, String description) {
        Organization org = findById(id);
        if (name != null && !name.isBlank()) {
            org.setName(name);
        }
        if (description != null) {
            org.setDescription(description);
        }
        return organizationRepository.save(org);
    }

    public void delete(UUID id) {
        Organization org = findById(id);
        organizationRepository.delete(org);
    }
}
