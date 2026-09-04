package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceConflictException;
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
import static org.mockito.ArgumentMatchers.eq;
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
        when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(UUID.class), any(String.class)))
                .thenReturn(Optional.empty());
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
                repositoryService.create(UUID.randomUUID(), "GITHUB",
                        "https://github.com/test/repo", "name", "main"));
    }

    @Test
    void createShouldThrowOnInvalidProvider() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "INVALID",
                        "https://github.com/test/repo", "name", "main"));
    }

    @Test
    void createShouldRejectNonGithubProvider() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITLAB",
                        "https://github.com/test/repo", "name", "main"));
    }

    @Test
    void createShouldDefaultBranchToMain() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(UUID.class), any(String.class)))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> {
            Repository r = invocation.getArgument(0);
            assertEquals("main", r.getDefaultBranch());
            return r;
        });

        repositoryService.create(UUID.randomUUID(), "GITHUB",
                "https://github.com/test/repo", "name", null);
        verify(repositoryRepository).save(any(Repository.class));
    }

    @Test
    void createShouldAcceptCustomBranch() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(UUID.class), any(String.class)))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> {
            Repository r = invocation.getArgument(0);
            assertEquals("develop", r.getDefaultBranch());
            return r;
        });

        repositoryService.create(UUID.randomUUID(), "GITHUB",
                "https://github.com/test/repo", "name", "develop");
        verify(repositoryRepository).save(any(Repository.class));
    }

    @Test
    void createShouldNormalizeUrl() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(UUID.class), any(String.class)))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> {
            Repository r = invocation.getArgument(0);
            assertEquals("https://github.com/test/repo", r.getRepositoryUrl());
            return r;
        });

        repositoryService.create(UUID.randomUUID(), "GITHUB",
                "https://github.com/test/repo/", "name", "main");
        verify(repositoryRepository).save(any(Repository.class));
    }

    @Test
    void createShouldNormalizeUrlRemovingGitSuffix() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(UUID.class), any(String.class)))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> {
            Repository r = invocation.getArgument(0);
            assertEquals("https://github.com/test/repo", r.getRepositoryUrl());
            return r;
        });

        repositoryService.create(UUID.randomUUID(), "GITHUB",
                "https://github.com/test/repo.git", "name", "main");
        verify(repositoryRepository).save(any(Repository.class));
    }

    @Test
    void createShouldDetectDuplicateRepository() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(UUID.class), any(String.class)))
                .thenReturn(Optional.of(repository));

        assertThrows(ResourceConflictException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB",
                        "https://github.com/test/repo", "test-repo", "main"));
    }

    @Test
    void createShouldDetectDuplicateWithTrailingSlash() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(UUID.class), eq("https://github.com/test/repo")))
                .thenReturn(Optional.of(repository));

        assertThrows(ResourceConflictException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB",
                        "https://github.com/test/repo/", "test-repo", "main"));
    }

    @Test
    void createShouldRejectBlankUrl() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB", "", "name", "main"));
    }

    @Test
    void createShouldRejectNullUrl() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB", null, "name", "main"));
    }

    @Test
    void createShouldRejectInvalidUrl() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB", "not-a-url", "name", "main"));
    }

    @Test
    void createShouldRejectNonGithubUrl() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB",
                        "https://gitlab.com/test/repo", "name", "main"));
    }

    @Test
    void createShouldRejectBlankName() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));

        assertThrows(BusinessRuleException.class, () ->
                repositoryService.create(UUID.randomUUID(), "GITHUB",
                        "https://github.com/test/repo", "", "main"));
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

    @Test
    void updateShouldNormalizeUrl() {
        when(repositoryRepository.findById(nullable(UUID.class))).thenReturn(Optional.of(repository));
        lenient().when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(), any(String.class)))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Repository result = repositoryService.update(UUID.randomUUID(),
                "https://github.com/test/newrepo/", "new-repo", "develop", null);

        assertEquals("https://github.com/test/newrepo", result.getRepositoryUrl());
    }

    @Test
    void updateShouldRejectDuplicateUrl() {
        UUID otherId = UUID.randomUUID();
        Repository otherRepo = mock(Repository.class);
        when(otherRepo.getId()).thenReturn(otherId);

        when(repositoryRepository.findById(nullable(UUID.class))).thenReturn(Optional.of(repository));
        lenient().when(repositoryRepository.findByProjectIdAndRepositoryUrl(any(), any(String.class)))
                .thenReturn(Optional.of(otherRepo));

        assertThrows(ResourceConflictException.class, () ->
                repositoryService.update(UUID.randomUUID(),
                        "https://github.com/test/other", "name", "main", null));
    }

    @Test
    void deleteShouldRemoveRepository() {
        when(repositoryRepository.findById(nullable(UUID.class))).thenReturn(Optional.of(repository));

        repositoryService.delete(UUID.randomUUID());

        verify(repositoryRepository).delete(repository);
    }

    @Test
    void getCloneUrlShouldReturnNormalizedUrl() {
        Repository repo = new Repository(project, Repository.ProviderType.GITHUB,
                "https://github.com/test/repo.git", "test-repo", "main");

        assertEquals("https://github.com/test/repo", repo.getCloneUrl());
    }

    @Test
    void normalizeUrlShouldStripTrailingSlash() {
        assertEquals("https://github.com/test/repo",
                Repository.normalizeUrl("https://github.com/test/repo/"));
    }

    @Test
    void normalizeUrlShouldStripGitSuffix() {
        assertEquals("https://github.com/test/repo",
                Repository.normalizeUrl("https://github.com/test/repo.git"));
    }

    @Test
    void normalizeUrlShouldStripTrailingSlashAndGitSuffix() {
        assertEquals("https://github.com/test/repo",
                Repository.normalizeUrl("https://github.com/test/repo.git/"));
    }

    @Test
    void normalizeUrlShouldHandleNull() {
        assertNull(Repository.normalizeUrl(null));
    }

    @Test
    void normalizeUrlShouldHandleBlank() {
        assertEquals("", Repository.normalizeUrl(""));
    }

    @Test
    void normalizeUrlShouldHandleWhitespace() {
        assertEquals("https://github.com/test/repo",
                Repository.normalizeUrl("  https://github.com/test/repo  "));
    }
}
