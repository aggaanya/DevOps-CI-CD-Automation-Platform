package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.*;
import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DomainCrudIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private PipelineRunRepository pipelineRunRepository;
    @Autowired private PipelineStageRepository pipelineStageRepository;
    @Autowired private PipelineJobRepository pipelineJobRepository;
    @Autowired private JobAttemptRepository jobAttemptRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ArtifactRepository artifactRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    private Organization org;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Acme Corp", "acme", "Acme Corporation"));
    }

    @Test
    void createAndGetOrganization() throws Exception {
        String body = """
                {"name": "New Org", "slug": "new-org", "description": "desc"}
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New Org"))
                .andExpect(jsonPath("$.slug").value("new-org"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void getOrganizationById() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + org.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Corp"));
    }

    @Test
    void listOrganizations() throws Exception {
        mockMvc.perform(get("/api/v1/organizations")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createAndGetProject() throws Exception {
        String body = String.format("""
                {"organizationId": "%s", "name": "API", "slug": "api", "description": "The API"}
                """, org.getId());

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("API"))
                .andExpect(jsonPath("$.organizationId").value(org.getId().toString()));
    }

    @Test
    void createAndGetRepository() throws Exception {
        Project project = projectRepository.save(new Project(org, "Repo Proj", "repo-proj", null));

        String body = String.format("""
                {"projectId": "%s", "provider": "GITHUB", "repositoryUrl": "https://github.com/test/repo", "repositoryName": "repo", "defaultBranch": "main"}
                """, project.getId());

        mockMvc.perform(post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("GITHUB"))
                .andExpect(jsonPath("$.repositoryName").value("repo"));
    }

    @Test
    void createAndGetPipeline() throws Exception {
        Project project = projectRepository.save(new Project(org, "Pipe Proj", "pipe-proj", null));

        String body = String.format("""
                {"projectId": "%s", "name": "CI Pipeline", "description": "Build and test"}
                """, project.getId());

        mockMvc.perform(post("/api/v1/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("CI Pipeline"));
    }

    @Test
    void fullHierarchyPersistence() throws Exception {
        Project project = projectRepository.save(new Project(org, "Full Proj", "full-proj", null));
        Repository repo = repositoryRepository.save(
                new Repository(project, Repository.ProviderType.GITHUB, "https://github.com/t/r", "r", "main"));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "Full Pipeline", null));
        PipelineVersion version = pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, "stages: [build]", "abc123", "admin"));
        PipelineRun run = pipelineRunRepository.save(
                new PipelineRun(version, repo, "abc123", "main", PipelineRun.TriggerType.MANUAL, "admin"));
        PipelineStage stage = pipelineStageRepository.save(new PipelineStage(run, "build", 0));
        PipelineJob job = pipelineJobRepository.save(new PipelineJob(stage, "maven", PipelineJob.JobType.BUILD));
        JobAttempt attempt = jobAttemptRepository.save(new JobAttempt(job, 1));
        Artifact artifact = artifactRepository.save(new Artifact(run, Artifact.ArtifactType.DOCKER_IMAGE, "img", "http://acr"));
        Deployment deployment = deploymentRepository.save(new Deployment(run, "staging"));
        WebhookEvent webhookEvent = webhookEventRepository.save(
                new WebhookEvent("GITHUB", "del-1", "push", repo, Map.of()));
        OutboxEvent outboxEvent = outboxEventRepository.save(
                new OutboxEvent("RUN_CREATED", "PipelineRun", run.getId(), "{}"));
        AuditEvent auditEvent = auditEventRepository.save(
                new AuditEvent("admin", "CREATED", "Pipeline", pipeline.getId()));

        assertNotNull(attempt.getId());
        assertNotNull(artifact.getId());
        assertNotNull(deployment.getId());
        assertNotNull(webhookEvent.getId());
        assertNotNull(outboxEvent.getId());
        assertNotNull(auditEvent.getId());
    }

    @Test
    void validationShouldRejectEmptyName() throws Exception {
        String body = """
                {"name": "", "slug": "test"}
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validationShouldRejectMissingOrgId() throws Exception {
        String body = """
                {"name": "Test", "slug": "test"}
                """;

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNonexistentShould404() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthEndpointStillWorks() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("cicd-control-plane"));
    }
}
