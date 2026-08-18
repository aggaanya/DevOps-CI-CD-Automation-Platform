package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.ResourceConflictException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.repository.OrganizationRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;

    public ProjectService(ProjectRepository projectRepository, OrganizationRepository organizationRepository) {
        this.projectRepository = projectRepository;
        this.organizationRepository = organizationRepository;
    }

    public Project create(UUID organizationId, String name, String slug, String description) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + organizationId));

        if (projectRepository.existsByOrganizationIdAndSlug(organizationId, slug)) {
            throw new ResourceConflictException("Project with slug '" + slug + "' already exists in this organization");
        }

        Project project = new Project(org, name, slug, description);
        return projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public Project findById(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Project> findByOrganizationId(UUID organizationId) {
        return projectRepository.findByOrganizationId(organizationId);
    }
}
