package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.*;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class DomainRepositoryIntegrationTest {

    @Autowired private EntityManager entityManager;
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

    @Test
    void fullHierarchyShouldPersist() {
        Organization org = organizationRepository.save(new Organization("Acme", "acme", "Acme Corp"));
        assertNotNull(org.getId());

        Project project = projectRepository.save(new Project(org, "API", "api", "The API"));
        assertEquals(org.getId(), project.getOrganization().getId());

        Repository repo = repositoryRepository.save(
                new Repository(project, Repository.ProviderType.GITHUB, "https://github.com/acme/api", "api", "main"));
        assertEquals(project.getId(), repo.getProject().getId());

        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "CI Pipeline", "Build and test"));
        assertEquals(project.getId(), pipeline.getProject().getId());

        PipelineVersion version = pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, "stages: [build]", "abc123", "admin"));
        assertEquals(pipeline.getId(), version.getPipeline().getId());

        PipelineRun run = pipelineRunRepository.save(
                new PipelineRun(version, repo, "abc123", "main", PipelineRun.TriggerType.MANUAL, "admin"));
        assertEquals(version.getId(), run.getPipelineVersion().getId());
        assertEquals(repo.getId(), run.getRepository().getId());

        PipelineStage stage = pipelineStageRepository.save(new PipelineStage(run, "build", 0));
        assertEquals(run.getId(), stage.getPipelineRun().getId());

        PipelineJob job = pipelineJobRepository.save(new PipelineJob(stage, "maven-build", PipelineJob.JobType.BUILD));
        assertEquals(stage.getId(), job.getPipelineStage().getId());

        JobAttempt attempt = jobAttemptRepository.save(new JobAttempt(job, 1));
        assertEquals(job.getId(), attempt.getJob().getId());

        Artifact artifact = artifactRepository.save(new Artifact(run, Artifact.ArtifactType.DOCKER_IMAGE, "api:latest", "http://acr/api"));
        assertEquals(run.getId(), artifact.getPipelineRun().getId());

        Deployment deployment = deploymentRepository.save(new Deployment(run, "staging"));
        assertEquals(run.getId(), deployment.getPipelineRun().getId());

        WebhookEvent webhookEvent = webhookEventRepository.save(
                new WebhookEvent("GITHUB", "delivery-1", "push", repo, Map.of("ref", "refs/heads/main")));
        assertEquals(repo.getId(), webhookEvent.getRepository().getId());

        OutboxEvent outboxEvent = outboxEventRepository.save(
                new OutboxEvent("RUN_CREATED", "PipelineRun", run.getId(), "{\"runId\":\"" + run.getId() + "\"}"));
        assertNotNull(outboxEvent.getId());

        AuditEvent auditEvent = auditEventRepository.save(
                new AuditEvent("admin", "PIPELINE_CREATED", "Pipeline", pipeline.getId()));
        assertNotNull(auditEvent.getId());
    }

    @Test
    void webhookDeliveryIdShouldBeUnique() {
        WebhookEvent event1 = new WebhookEvent("GITHUB", "del-001", "push", null, Map.of());
        webhookEventRepository.save(event1);
        entityManager.flush();

        WebhookEvent duplicate = new WebhookEvent("GITHUB", "del-001", "push", null, Map.of());
        assertThrows(Exception.class, () -> {
            webhookEventRepository.save(duplicate);
            entityManager.flush();
        });
    }

    @Test
    void outboxEventShouldPersistWithJsonPayload() {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent("RUN_CREATED", "PipelineRun", UUID.randomUUID(), "{\"key\":\"value\"}"));

        OutboxEvent found = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals("RUN_CREATED", found.getEventType());
        assertEquals("{\"key\":\"value\"}", found.getPayload());
    }

    @Test
    void auditEventShouldPersistMetadata() {
        AuditEvent event = auditEventRepository.save(
                new AuditEvent("user@test.com", "PROJECT_CREATED", "Project", UUID.randomUUID()));
        event.setCorrelationId("corr-123");
        event.setIpAddress("127.0.0.1");
        event.setMetadata(Map.of("name", "test-project"));
        auditEventRepository.save(event);

        AuditEvent found = auditEventRepository.findById(event.getId()).orElseThrow();
        assertEquals("corr-123", found.getCorrelationId());
        assertEquals("test-project", found.getMetadata().get("name"));
    }

    @Test
    void artifactShouldPersist() {
        Organization org = organizationRepository.save(new Organization("Org", "org1", null));
        Project project = projectRepository.save(new Project(org, "P", "p1", null));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "PL", null));
        PipelineVersion version = pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, "yaml", null, null));
        PipelineRun run = pipelineRunRepository.save(
                new PipelineRun(version, null, "sha", "main", PipelineRun.TriggerType.MANUAL, "user"));

        Artifact artifact = artifactRepository.save(
                new Artifact(run, Artifact.ArtifactType.DOCKER_IMAGE, "image", "http://acr/img"));

        assertEquals(1, artifactRepository.findByPipelineRunId(run.getId()).size());
    }

    @Test
    void deploymentShouldPersist() {
        Organization org = organizationRepository.save(new Organization("Org", "org2", null));
        Project project = projectRepository.save(new Project(org, "P", "p2", null));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "PL", null));
        PipelineVersion version = pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, "yaml", null, null));
        PipelineRun run = pipelineRunRepository.save(
                new PipelineRun(version, null, "sha", "main", PipelineRun.TriggerType.MANUAL, "user"));

        Deployment deployment = deploymentRepository.save(new Deployment(run, "production"));
        deployment.setStatus(Deployment.DeploymentStatus.SUCCESS);
        deployment.setEndpoint("https://app.azure.com");
        deploymentRepository.save(deployment);

        assertEquals(1, deploymentRepository.findByPipelineRunId(run.getId()).size());
    }

    @Test
    void organizationSlugShouldBeUnique() {
        organizationRepository.save(new Organization("Org1", "unique-slug", null));
        entityManager.flush();

        assertThrows(Exception.class, () -> {
            organizationRepository.save(new Organization("Org2", "unique-slug", null));
            entityManager.flush();
        });
    }

    @Test
    void projectSlugShouldBeUniquePerOrg() {
        Organization org = organizationRepository.save(new Organization("Org", "org3", null));
        projectRepository.save(new Project(org, "P1", "slug", null));
        entityManager.flush();

        assertThrows(Exception.class, () -> {
            projectRepository.save(new Project(org, "P2", "slug", null));
            entityManager.flush();
        });
    }

    @Test
    void pipelineVersionsShouldListDescending() {
        Organization org = organizationRepository.save(new Organization("Org", "org4", null));
        Project project = projectRepository.save(new Project(org, "P", "p4", null));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "PL", null));

        pipelineVersionRepository.save(new PipelineVersion(pipeline, 1, "v1", null, null));
        pipelineVersionRepository.save(new PipelineVersion(pipeline, 2, "v2", null, null));

        var versions = pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipeline.getId());
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).getVersion());
        assertEquals(1, versions.get(1).getVersion());
    }

    @Test
    void pipelineStagesShouldListOrdered() {
        Organization org = organizationRepository.save(new Organization("Org", "org5", null));
        Project project = projectRepository.save(new Project(org, "P", "p5", null));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "PL", null));
        PipelineVersion version = pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, "yaml", null, null));
        PipelineRun run = pipelineRunRepository.save(
                new PipelineRun(version, null, "sha", "main", PipelineRun.TriggerType.MANUAL, "user"));

        pipelineStageRepository.save(new PipelineStage(run, "build", 0));
        pipelineStageRepository.save(new PipelineStage(run, "test", 1));

        var stages = pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(2, stages.size());
        assertEquals("build", stages.get(0).getName());
        assertEquals("test", stages.get(1).getName());
    }

    @Test
    void jobAttemptsShouldListOrdered() {
        Organization org = organizationRepository.save(new Organization("Org", "org6", null));
        Project project = projectRepository.save(new Project(org, "P", "p6", null));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "PL", null));
        PipelineVersion version = pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, "yaml", null, null));
        PipelineRun run = pipelineRunRepository.save(
                new PipelineRun(version, null, "sha", "main", PipelineRun.TriggerType.MANUAL, "user"));
        PipelineStage stage = pipelineStageRepository.save(new PipelineStage(run, "build", 0));
        PipelineJob job = pipelineJobRepository.save(new PipelineJob(stage, "maven", PipelineJob.JobType.BUILD));

        jobAttemptRepository.save(new JobAttempt(job, 1));
        jobAttemptRepository.save(new JobAttempt(job, 2));

        var attempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
        assertEquals(2, attempts.size());
        assertEquals(1, attempts.get(0).getAttemptNumber());
        assertEquals(2, attempts.get(1).getAttemptNumber());
    }

    @Test
    void outboxPendingEventsShouldBeQueryable() {
        OutboxEvent pending = outboxEventRepository.save(
                new OutboxEvent("TYPE", "Agg", UUID.randomUUID(), "{}"));
        pending.setStatus(OutboxEvent.OutboxEventStatus.PENDING);
        outboxEventRepository.save(pending);

        OutboxEvent published = outboxEventRepository.save(
                new OutboxEvent("TYPE", "Agg", UUID.randomUUID(), "{}"));
        published.setStatus(OutboxEvent.OutboxEventStatus.PUBLISHED);
        outboxEventRepository.save(published);

        var pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxEventStatus.PENDING);
        assertTrue(pendingEvents.stream().allMatch(e -> e.getStatus() == OutboxEvent.OutboxEventStatus.PENDING));
    }
}
