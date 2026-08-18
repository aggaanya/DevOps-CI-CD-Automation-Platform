package com.cicd.platform.controlplane.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "repositories", uniqueConstraints = {
        @UniqueConstraint(name = "uq_repositories_project_url", columnNames = {"project_id", "repository_url"})
}, indexes = {
        @Index(name = "idx_repositories_project_id", columnList = "project_id"),
        @Index(name = "idx_repositories_status", columnList = "status")
})
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_repositories_project"))
    private Project project;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ProviderType provider;

    @Column(name = "repository_url", nullable = false, length = 1024)
    private String repositoryUrl;

    @Column(name = "repository_name", nullable = false, length = 255)
    private String repositoryName;

    @Column(name = "default_branch", nullable = false, length = 255)
    private String defaultBranch = "main";

    @Column(name = "webhook_id", length = 255)
    private String webhookId;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RepositoryStatus status = RepositoryStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Repository() {}

    public Repository(Project project, ProviderType provider, String repositoryUrl,
                      String repositoryName, String defaultBranch) {
        this.project = project;
        this.provider = provider;
        this.repositoryUrl = repositoryUrl;
        this.repositoryName = repositoryName;
        this.defaultBranch = defaultBranch;
    }

    public UUID getId() { return id; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public ProviderType getProvider() { return provider; }
    public void setProvider(ProviderType provider) { this.provider = provider; }

    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public String getWebhookId() { return webhookId; }
    public void setWebhookId(String webhookId) { this.webhookId = webhookId; }

    public RepositoryStatus getStatus() { return status; }
    public void setStatus(RepositoryStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Repository that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public enum ProviderType {
        GITHUB, GITLAB, BITBUCKET
    }

    public enum RepositoryStatus {
        ACTIVE, INACTIVE, PENDING
    }
}
