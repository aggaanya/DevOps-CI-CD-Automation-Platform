package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.ResourceConflictException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.repository.OrganizationRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private ProjectService projectService;

    private Organization org;
    private Project project;

    @BeforeEach
    void setUp() {
        org = new Organization("Test Org", "test-org", "desc");
        project = new Project(org, "Test Project", "test-project", "A project");
    }

    @Test
    void createShouldPersistProject() {
        when(organizationRepository.findById(any(UUID.class))).thenReturn(Optional.of(org));
        when(projectRepository.existsByOrganizationIdAndSlug(any(UUID.class), eq("test-project"))).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenReturn(project);

        Project result = projectService.create(UUID.randomUUID(), "Test Project", "test-project", "A project");

        assertNotNull(result);
        assertEquals("Test Project", result.getName());
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void createShouldThrowWhenOrgNotFound() {
        when(organizationRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                projectService.create(UUID.randomUUID(), "name", "slug", "desc"));
    }

    @Test
    void createShouldThrowOnDuplicateSlug() {
        when(organizationRepository.findById(any(UUID.class))).thenReturn(Optional.of(org));
        when(projectRepository.existsByOrganizationIdAndSlug(any(UUID.class), eq("test-project"))).thenReturn(true);

        assertThrows(ResourceConflictException.class, () ->
                projectService.create(UUID.randomUUID(), "name", "test-project", "desc"));
    }

    @Test
    void findByIdShouldReturnExisting() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        Project result = projectService.findById(UUID.randomUUID());

        assertNotNull(result);
        assertEquals("Test Project", result.getName());
    }

    @Test
    void findByIdShouldThrowWhenNotFound() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                projectService.findById(UUID.randomUUID()));
    }
}
