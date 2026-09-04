package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceConflictException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.entity.Repository;
import com.cicd.platform.controlplane.domain.entity.Repository.ProviderType;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import com.cicd.platform.controlplane.domain.repository.RepositoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class RepositoryService {

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^https?://github\\.com/[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+(/.*)?$"
    );

    private final RepositoryRepository repositoryRepository;
    private final ProjectRepository projectRepository;

    public RepositoryService(RepositoryRepository repositoryRepository, ProjectRepository projectRepository) {
        this.repositoryRepository = repositoryRepository;
        this.projectRepository = projectRepository;
    }

    public Repository create(UUID projectId, String provider, String repositoryUrl,
                             String repositoryName, String defaultBranch) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        if (repositoryName == null || repositoryName.isBlank()) {
            throw new BusinessRuleException("Repository name cannot be blank");
        }

        ProviderType providerType;
        try {
            providerType = ProviderType.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    "Unsupported provider: " + provider + ". Supported providers: GITHUB, GITLAB, BITBUCKET");
        }

        if (providerType != ProviderType.GITHUB) {
            throw new BusinessRuleException(
                    "Only GITHUB provider is supported in this module. Provider: " + provider);
        }

        validateRepositoryUrl(repositoryUrl, providerType);

        String branch = (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch;

        String normalizedUrl = Repository.normalizeUrl(repositoryUrl);

        repositoryRepository.findByProjectIdAndRepositoryUrl(projectId, normalizedUrl)
                .ifPresent(existing -> {
                    throw new ResourceConflictException(
                            "Repository with URL '" + normalizedUrl + "' already exists in this project");
                });

        Repository repo = new Repository(project, providerType, normalizedUrl, repositoryName, branch);
        return repositoryRepository.save(repo);
    }

    @Transactional(readOnly = true)
    public Repository findById(UUID id) {
        return repositoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Repository> findByProjectId(UUID projectId) {
        return repositoryRepository.findByProjectId(projectId);
    }

    public Repository update(UUID id, String repositoryUrl, String repositoryName,
                             String defaultBranch, Repository.RepositoryStatus status) {
        Repository repo = findById(id);
        if (repositoryUrl != null && !repositoryUrl.isBlank()) {
            validateRepositoryUrl(repositoryUrl, repo.getProvider());
            String normalizedUrl = Repository.normalizeUrl(repositoryUrl);
            repositoryRepository.findByProjectIdAndRepositoryUrl(
                            repo.getProject().getId(), normalizedUrl)
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            throw new ResourceConflictException(
                                    "Repository with URL '" + normalizedUrl + "' already exists in this project");
                        }
                    });
            repo.setRepositoryUrl(normalizedUrl);
        }
        if (repositoryName != null && !repositoryName.isBlank()) {
            repo.setRepositoryName(repositoryName);
        }
        if (defaultBranch != null && !defaultBranch.isBlank()) {
            repo.setDefaultBranch(defaultBranch);
        }
        if (status != null) {
            repo.setStatus(status);
        }
        return repositoryRepository.save(repo);
    }

    public void delete(UUID id) {
        Repository repo = findById(id);
        repositoryRepository.delete(repo);
    }

    private void validateRepositoryUrl(String url, ProviderType provider) {
        if (url == null || url.isBlank()) {
            throw new BusinessRuleException("Repository URL cannot be blank");
        }

        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new BusinessRuleException("Repository URL must be a valid URL");
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Repository URL must be a valid URL: " + url);
        }

        if (provider == ProviderType.GITHUB && !GITHUB_URL_PATTERN.matcher(url).matches()) {
            throw new BusinessRuleException(
                    "Repository URL must be a valid GitHub repository URL (https://github.com/owner/repo)");
        }
    }
}
