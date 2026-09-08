package com.cicd.platform.controlplane.security;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceConflictException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.PlatformUser;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.entity.ProjectMembership;
import com.cicd.platform.controlplane.domain.repository.ArtifactRepository;
import com.cicd.platform.controlplane.domain.repository.DeploymentRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectMembershipRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import com.cicd.platform.controlplane.domain.repository.RepositoryRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectAccessService {

    private final CurrentUserResolver currentUserResolver;
    private final ProjectMembershipRepository membershipRepository;
    private final ProjectRepository projectRepository;
    private final RepositoryRepository repositoryRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final ArtifactRepository artifactRepository;
    private final DeploymentRepository deploymentRepository;
    private final AuthUserService authUserService;

    public ProjectAccessService(CurrentUserResolver currentUserResolver,
                                ProjectMembershipRepository membershipRepository,
                                ProjectRepository projectRepository,
                                RepositoryRepository repositoryRepository,
                                PipelineRepository pipelineRepository,
                                PipelineVersionRepository pipelineVersionRepository,
                                PipelineRunRepository pipelineRunRepository,
                                ArtifactRepository artifactRepository,
                                DeploymentRepository deploymentRepository,
                                AuthUserService authUserService) {
        this.currentUserResolver = currentUserResolver;
        this.membershipRepository = membershipRepository;
        this.projectRepository = projectRepository;
        this.repositoryRepository = repositoryRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.artifactRepository = artifactRepository;
        this.deploymentRepository = deploymentRepository;
        this.authUserService = authUserService;
    }

    public CurrentUser currentUser() {
        return currentUserResolver.require();
    }

    public void assertAuthenticated() {
        currentUserResolver.require();
    }

    public void assertGlobalAdmin() {
        CurrentUser user = currentUser();
        if (!user.isAdmin()) {
            throw new AccessDeniedException("Global ADMIN role required");
        }
    }

    public boolean canAccess(UUID projectId) {
        CurrentUser user = currentUser();
        if (user.isAdmin()) {
            return true;
        }
        return membershipRepository.findByProjectIdAndUserKeycloakSubject(projectId, user.subject()).isPresent();
    }

    public void assertProjectRead(UUID projectId) {
        if (!canAccess(projectId)) {
            throw new AccessDeniedException("No access to project " + projectId);
        }
    }

    public void assertProjectWrite(UUID projectId) {
        assertProjectRole(projectId, ApiRole.DEVELOPER, "write");
    }

    public void assertProjectAdmin(UUID projectId) {
        assertProjectRole(projectId, ApiRole.ADMIN, "administer");
    }

    private void assertProjectRole(UUID projectId, ApiRole required, String action) {
        CurrentUser user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        ProjectMembership membership = membershipRepository
                .findByProjectIdAndUserKeycloakSubject(projectId, user.subject())
                .orElseThrow(() -> new AccessDeniedException("You are not a member of project " + projectId));
        if (!satisfies(membership.getRole(), required)) {
            throw new AccessDeniedException("Role " + required + " required to " + action + " in project " + projectId);
        }
    }

    private boolean satisfies(ProjectMembership.ProjectRole membershipRole, ApiRole required) {
        return switch (required) {
            case ADMIN -> membershipRole == ProjectMembership.ProjectRole.ADMIN;
            case DEVELOPER ->
                    membershipRole == ProjectMembership.ProjectRole.ADMIN || membershipRole == ProjectMembership.ProjectRole.DEVELOPER;
            case VIEWER -> true;
        };
    }

    @Transactional(readOnly = true)
    public List<Project> findAccessibleProjects(UUID organizationId) {
        CurrentUser user = currentUser();
        if (user.isAdmin()) {
            return projectRepository.findByOrganizationId(organizationId);
        }
        return membershipsOfCurrent().stream()
                .map(ProjectMembership::getProject)
                .filter(project -> project.getOrganization().getId().equals(organizationId))
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Project> findAccessibleProjects() {
        CurrentUser user = currentUser();
        if (user.isAdmin()) {
            return projectRepository.findAll();
        }
        return membershipsOfCurrent().stream()
                .map(ProjectMembership::getProject)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectMembership> membershipsOfCurrent() {
        CurrentUser user = currentUser();
        return user.isAdmin() ? List.of() : membershipRepository.findByUserKeycloakSubject(user.subject());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveProjectForRepository(UUID repositoryId) {
        return repositoryRepository.findById(repositoryId).map(repository -> repository.getProject().getId());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveProjectForPipeline(UUID pipelineId) {
        return pipelineRepository.findById(pipelineId).map(pipeline -> pipeline.getProject().getId());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveProjectForVersion(UUID versionId) {
        return pipelineVersionRepository.findById(versionId).map(version -> version.getPipeline().getProject().getId());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveProjectForRun(UUID runId) {
        return pipelineRunRepository.findById(runId).map(run -> run.getPipelineVersion().getPipeline().getProject().getId());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveProjectForArtifact(UUID artifactId) {
        return artifactRepository.findById(artifactId).map(artifact -> artifact.getPipelineRun().getPipelineVersion().getPipeline().getProject().getId());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveProjectForDeployment(UUID deploymentId) {
        return deploymentRepository.findById(deploymentId).map(deployment -> deployment.getPipelineRun().getPipelineVersion().getPipeline().getProject().getId());
    }

    public void assertProjectRead(Optional<UUID> projectId) {
        projectId.ifPresent(this::assertProjectRead);
    }

    public void assertProjectWrite(Optional<UUID> projectId) {
        projectId.ifPresent(this::assertProjectWrite);
    }

    public void assertProjectAdmin(Optional<UUID> projectId) {
        projectId.ifPresent(this::assertProjectAdmin);
    }

    @Transactional(readOnly = true)
    public List<ProjectMembership> membershipsOfProject(UUID projectId) {
        assertProjectAdmin(projectId);
        return membershipRepository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectMembership grantMembership(UUID projectId, String keycloakSubject, String role) {
        assertProjectAdmin(projectId);
        ProjectMembership.ProjectRole projectRole = parseRole(role);
        Project project = projectById(projectId);
        if (membershipRepository.existsByProjectIdAndUserKeycloakSubject(projectId, keycloakSubject)) {
            throw new ResourceConflictException("User " + keycloakSubject + " is already a member of project " + projectId);
        }
        PlatformUser user = authUserService.findBySubject(keycloakSubject)
                .orElseGet(() -> authUserService.findOrCreate(new CurrentUser(keycloakSubject, keycloakSubject, null, keycloakSubject, Set.of())));
        return membershipRepository.save(new ProjectMembership(project, user, projectRole));
    }

    @Transactional
    public ProjectMembership updateMembershipRole(UUID membershipId, String role) {
        ProjectMembership.ProjectRole projectRole = parseRole(role);
        ProjectMembership membership = membershipWithProjectById(membershipId);
        assertProjectAdmin(membership.getProject().getId());
        membership.setRole(projectRole);
        return membershipRepository.save(membership);
    }

    @Transactional
    public void revokeMembership(UUID membershipId) {
        ProjectMembership membership = membershipById(membershipId);
        assertProjectAdmin(membership.getProject().getId());
        membershipRepository.delete(membership);
    }

    private ProjectMembership.ProjectRole parseRole(String role) {
        try {
            return ProjectMembership.ProjectRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Role must be one of ADMIN, DEVELOPER, VIEWER");
        }
    }

    private Project projectById(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));
    }

    private ProjectMembership membershipById(UUID membershipId) {
        return membershipRepository.findById(membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Project membership not found with id: " + membershipId));
    }

    private ProjectMembership membershipWithProjectById(UUID membershipId) {
        return membershipRepository.findWithProjectAndUserById(membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Project membership not found with id: " + membershipId));
    }
}