package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.entity.Repository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import com.cicd.platform.controlplane.domain.repository.RepositoryRepository;
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
class RepositoryServiceTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private RepositoryService repositoryService;

    private Project project;
    private Repository repository;

    @BeforeEach
    void setUp() {
        Organization org = new Organization("Test Org", "test-org", "desc");
        project = new Project(org, "Test Project", "test-project", "desc");
        repository = new Repository(project, Repository.ProviderType.GITHUB,
                "https://github.com/test/repo", "test-repo", "main");
    }

    @Test
    void createShouldPersistRepository() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.save(any(Repository.class))).thenReturn(repository);

        Repository result = repositoryService.create(
                UUID.randomUUID(), "GITHUB", "https://github.com/test/repo", "test-repo", "main");

        assertNotNull(result);
        assertEquals("test-repo", result.getRepositoryName());
        assertEquals(Repository.ProviderType.GITHUB, result.getProvider());
        verify(repositoryRepository).save(any(Repository.class));
    }

    @Test
    void createShouldThrowWhenProjectNotFound() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB", "url", "name", "main"));
    }

    @Test
    void createShouldThrowOnInvalidProvider() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "INVALID", "url", "name", "main"));
    }

    @Test
    void createShouldDefaultBranchToMain() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> {
            Repository r = invocation.getArgument(0);
            assertEquals("main", r.getDefaultBranch());
            return r;
        });

        repositoryService.create(UUID.randomUUID(), "github", "url", "name", null);
        verify(repositoryRepository).save(any(Repository.class));
    }

    @Test
    void findByIdShouldReturnExisting() {
        when(repositoryRepository.findById(any(UUID.class))).thenReturn(Optional.of(repository));

        Repository result = repositoryService.findById(UUID.randomUUID());

        assertNotNull(result);
        assertEquals("test-repo", result.getRepositoryName());
    }

    @Test
    void findByIdShouldThrowWhenNotFound() {
        when(repositoryRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                repositoryService.findById(UUID.randomUUID()));
    }
}
