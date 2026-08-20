package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.entity.Repository;
import com.cicd.platform.controlplane.domain.entity.Repository.ProviderType;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import com.cicd.platform.controlplane.domain.repository.RepositoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RepositoryService {

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

        ProviderType providerType;
        try {
            providerType = ProviderType.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    "Unsupported provider: " + provider + ". Supported providers: GITHUB, GITLAB, BITBUCKET");
        }

        String branch = (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch;

        Repository repo = new Repository(project, providerType, repositoryUrl, repositoryName, branch);
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
            repo.setRepositoryUrl(repositoryUrl);
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
}
