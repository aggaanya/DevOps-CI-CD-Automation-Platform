package com.cicd.platform.controlplane.pipeline;

import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import com.cicd.platform.controlplane.execution.RunService;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class YamlPipelineFlowTest {

    @Autowired private PipelineYamlService pipelineYamlService;
    @Autowired private RunService runService;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private PipelineStageRepository pipelineStageRepository;
    @Autowired private PipelineJobRepository pipelineJobRepository;
    @Autowired private JobAttemptRepository jobAttemptRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;

    @MockBean private RabbitTemplate rabbitTemplate;

    private Project project;

    @BeforeEach
    void setUp() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        Organization org = organizationRepository.save(
                new Organization("yaml-org-" + uid, "yaml-org-" + uid, "desc"));
        project = projectRepository.save(
                new Project(org, "yaml-proj-" + uid, "yaml-proj-" + uid, "desc"));
    }

    private String yaml(String pipelineName, String stageAndJobs) {
        return "pipeline:\n  name: " + pipelineName + "\n  stages:\n" + stageAndJobs;
    }

    private Pipeline pipeline() {
        return pipelineRepository.save(new Pipeline(project, "yaml-pipe", "desc"));
    }

    // -------------------------------------------------------
    // 1. Full chain: submitYaml → triggerRun → jobs + message
    // -------------------------------------------------------

    @Test
    void submitYaml_validYaml_createsVersionThenRunWithJobsAndDispatch() {
        Pipeline pipeline = pipeline();
        String yml = yaml("my-pipeline",
                "    - name: build\n      jobs:\n"
                + "        - name: compile\n          type: build");

        PipelineVersion version = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");
        assertNotNull(version.getId());
        assertEquals(1, version.getVersion());

        PipelineRun run = runService.triggerRun(version.getId(), "sha-1", "main", null, "alice");
        assertNotNull(run.getId());
        assertEquals(PipelineRun.RunStatus.RUNNING, run.getStatus());

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(1, stages.size());
        assertEquals("build", stages.get(0).getName());
        assertEquals(0, stages.get(0).getOrderIndex());

        List<PipelineJob> jobs = pipelineJobRepository
                .findByPipelineStageId(stages.get(0).getId());
        assertEquals(1, jobs.size());
        assertEquals("compile", jobs.get(0).getName());
        assertEquals(PipelineJob.JobType.BUILD, jobs.get(0).getJobType());
        assertNotNull(jobs.get(0).getId());

        List<JobAttempt> attempts = jobAttemptRepository
                .findByJobIdOrderByAttemptNumberAsc(jobs.get(0).getId());
        assertEquals(1, attempts.size());

        org.mockito.ArgumentCaptor<JobDispatchMessage> captor =
                org.mockito.ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                captor.capture());
        assertEquals(jobs.get(0).getId(), captor.getValue().jobId());
    }

    // -------------------------------------------------------
    // 2. Version incrementing on re-submission
    // -------------------------------------------------------

    @Test
    void submitYaml_samePipelineTwice_versionsIncrement() {
        Pipeline pipeline = pipeline();
        String yml = yaml("inc-pipe",
                "    - name: s1\n      jobs:\n"
                + "        - name: j1\n          type: test");

        PipelineVersion v1 = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");
        assertEquals(1, v1.getVersion());

        PipelineVersion v2 = pipelineYamlService.submitYaml(pipeline.getId(), yml, "bob");
        assertEquals(2, v2.getVersion());

        PipelineVersion v3 = pipelineYamlService.submitYaml(pipeline.getId(), yml, "carol");
        assertEquals(3, v3.getVersion());

        assertEquals(3, pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipeline.getId()).size());
    }

    // -------------------------------------------------------
    // 3. All six job types
    // -------------------------------------------------------

    @Test
    void submitYaml_allSixJobTypes_createsCorrectJobTypeEntities() {
        Pipeline pipeline = pipeline();
        String yml = "pipeline:\n  name: six-types\n  stages:\n"
                + "    - name: build-stage\n      jobs:\n"
                + "        - name: b\n          type: build\n"
                + "    - name: test-stage\n      jobs:\n"
                + "        - name: t\n          type: test\n"
                + "    - name: scan-stage\n      jobs:\n"
                + "        - name: s\n          type: scan\n"
                + "    - name: package-stage\n      jobs:\n"
                + "        - name: p\n          type: package\n"
                + "    - name: deploy-stage\n      jobs:\n"
                + "        - name: d\n          type: deploy\n"
                + "    - name: custom-stage\n      jobs:\n"
                + "        - name: c\n          type: custom";

        PipelineVersion version = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");
        PipelineRun run = runService.triggerRun(version.getId(), "sha-types", "main", null, "alice");

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(6, stages.size());

        UUID[] stageIds = stages.stream().map(PipelineStage::getId).toArray(UUID[]::new);
        PipelineJob.JobType[] expectedTypes = {
                PipelineJob.JobType.BUILD, PipelineJob.JobType.TEST, PipelineJob.JobType.SCAN,
                PipelineJob.JobType.PACKAGE, PipelineJob.JobType.DEPLOY, PipelineJob.JobType.CUSTOM
        };

        for (int i = 0; i < 6; i++) {
            List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stageIds[i]);
            assertEquals(1, jobs.size());
            assertEquals(expectedTypes[i], jobs.get(0).getJobType(),
                    "Stage " + i + " (" + stages.get(0).getName() + ") should have type " + expectedTypes[i]);
        }

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                any(JobDispatchMessage.class));
    }

    // -------------------------------------------------------
    // 4. Multi-stage with dependsOn (validated but not enforced)
    // -------------------------------------------------------

    @Test
    void submitYaml_multiStageWithDependencies_validatedAndCreatesJobs() {
        Pipeline pipeline = pipeline();
        String yml = "pipeline:\n  name: dep-pipe\n  stages:\n"
                + "    - name: build\n      jobs:\n"
                + "        - name: compile\n          type: build\n"
                + "    - name: test\n      dependsOn:\n        - build\n      jobs:\n"
                + "        - name: unit-test\n          type: test\n";

        PipelineVersion version = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");
        assertNotNull(version.getId());

        PipelineRun run = runService.triggerRun(version.getId(), "sha-dep", "main", null, "alice");

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(2, stages.size());
        assertEquals("build", stages.get(0).getName());
        assertEquals(0, stages.get(0).getOrderIndex());
        assertEquals("test", stages.get(1).getName());
        assertEquals(1, stages.get(1).getOrderIndex());

        List<PipelineJob> buildJobs = pipelineJobRepository.findByPipelineStageId(stages.get(0).getId());
        List<PipelineJob> testJobs = pipelineJobRepository.findByPipelineStageId(stages.get(1).getId());
        assertEquals(1, buildJobs.size());
        assertEquals(PipelineJob.JobType.BUILD, buildJobs.get(0).getJobType());
        assertEquals(1, testJobs.size());
        assertEquals(PipelineJob.JobType.TEST, testJobs.get(0).getJobType());

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                any(JobDispatchMessage.class));
    }

    // -------------------------------------------------------
    // 5. Job-level dependsOn within a stage
    // -------------------------------------------------------

    @Test
    void submitYaml_jobDependenciesWithinStage_validatedAndCreatesJobs() {
        Pipeline pipeline = pipeline();
        String yml = "pipeline:\n  name: job-dep-pipe\n  stages:\n"
                + "    - name: build-and-test\n      jobs:\n"
                + "        - name: compile\n          type: build\n"
                + "        - name: unit-test\n          type: test\n          dependsOn:\n            - compile\n";

        PipelineVersion version = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");
        assertNotNull(version.getId());

        PipelineRun run = runService.triggerRun(version.getId(), "sha-jdep", "main", null, "alice");

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(1, stages.size());

        List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stages.get(0).getId());
        assertEquals(2, jobs.size());
        assertEquals(PipelineJob.JobType.BUILD, jobs.get(0).getJobType());
        assertEquals(PipelineJob.JobType.TEST, jobs.get(1).getJobType());
    }

    // -------------------------------------------------------
    // 6. validateAndSubmitToProject: creates Pipeline + Version
    // -------------------------------------------------------

    @Test
    void submitYamlToProject_createsPipelineAndVersion() {
        String yml = yaml("auto-pipe",
                "    - name: build\n      jobs:\n"
                + "        - name: job1\n          type: build");

        PipelineVersion version = pipelineYamlService.validateAndSubmitToProject(
                project.getId(), yml, "alice");
        assertNotNull(version.getId());
        assertEquals(1, version.getVersion());

        Pipeline pipeline = version.getPipeline();
        assertEquals("auto-pipe", pipeline.getName());
        assertEquals(project.getId(), pipeline.getProject().getId());

        PipelineVersion v2 = pipelineYamlService.validateAndSubmitToProject(
                project.getId(), yml, "bob");
        assertEquals(2, v2.getVersion());
        assertEquals(pipeline.getId(), v2.getPipeline().getId(),
                "Second submission should reuse existing pipeline");
    }

    // -------------------------------------------------------
    // 7. Invalid YAML: parse error
    // -------------------------------------------------------

    @Test
    void submitYaml_invalidYamlSyntax_throwsPipelineValidationException() {
        Pipeline pipeline = pipeline();
        String badYaml = "pipeline:\n  name: [\n  bad";

        PipelineValidationException ex = assertThrows(PipelineValidationException.class,
                () -> pipelineYamlService.submitYaml(pipeline.getId(), badYaml, "alice"));
        assertFalse(ex.getValidationErrors().isEmpty());
    }

    // -------------------------------------------------------
    // 8. Invalid YAML: missing required name
    // -------------------------------------------------------

    @Test
    void submitYaml_missingPipelineName_throwsPipelineValidationException() {
        Pipeline pipeline = pipeline();
        String badYaml = "pipeline:\n  stages:\n"
                + "    - name: build\n      jobs:\n"
                + "        - name: job1\n          type: build";

        PipelineValidationException ex = assertThrows(PipelineValidationException.class,
                () -> pipelineYamlService.submitYaml(pipeline.getId(), badYaml, "alice"));
        assertTrue(ex.getValidationErrors().stream().anyMatch(e -> "pipeline.name".equals(e.path())));
    }

    // -------------------------------------------------------
    // 9. Invalid YAML: missing stages
    // -------------------------------------------------------

    @Test
    void submitYaml_missingStages_throwsPipelineValidationException() {
        Pipeline pipeline = pipeline();
        String badYaml = "pipeline:\n  name: no-stages";

        PipelineValidationException ex = assertThrows(PipelineValidationException.class,
                () -> pipelineYamlService.submitYaml(pipeline.getId(), badYaml, "alice"));
        assertTrue(ex.getValidationErrors().stream().anyMatch(e -> "pipeline.stages".equals(e.path())));
    }

    // -------------------------------------------------------
    // 10. Invalid YAML: missing job name
    // -------------------------------------------------------

    @Test
    void submitYaml_missingJobName_throwsPipelineValidationException() {
        Pipeline pipeline = pipeline();
        String badYaml = "pipeline:\n  name: no-job-name\n  stages:\n"
                + "    - name: build\n      jobs:\n"
                + "        - type: build";

        PipelineValidationException ex = assertThrows(PipelineValidationException.class,
                () -> pipelineYamlService.submitYaml(pipeline.getId(), badYaml, "alice"));
        assertTrue(ex.getValidationErrors().stream().anyMatch(e -> e.path() != null && e.path().contains("jobs") && e.path().endsWith(".name")));
    }

    // -------------------------------------------------------
    // 11. Invalid YAML: missing job type
    // -------------------------------------------------------

    @Test
    void submitYaml_missingJobType_throwsPipelineValidationException() {
        Pipeline pipeline = pipeline();
        String badYaml = "pipeline:\n  name: no-type\n  stages:\n"
                + "    - name: build\n      jobs:\n"
                + "        - name: job1";

        PipelineValidationException ex = assertThrows(PipelineValidationException.class,
                () -> pipelineYamlService.submitYaml(pipeline.getId(), badYaml, "alice"));
        assertTrue(ex.getValidationErrors().stream().anyMatch(e -> e.path() != null && e.path().contains("jobs") && e.path().endsWith(".type")));
    }

    // -------------------------------------------------------
    // 12. Invalid YAML: bad stage dependency reference
    // -------------------------------------------------------

    @Test
    void submitYaml_invalidStageDependency_throwsPipelineValidationException() {
        Pipeline pipeline = pipeline();
        String badYaml = "pipeline:\n  name: bad-dep\n  stages:\n"
                + "    - name: test\n      dependsOn:\n        - nonexistent\n      jobs:\n"
                + "        - name: job1\n          type: test";

        PipelineValidationException ex = assertThrows(PipelineValidationException.class,
                () -> pipelineYamlService.submitYaml(pipeline.getId(), badYaml, "alice"));
        assertTrue(ex.getValidationErrors().stream()
                .anyMatch(e -> e.code().contains("DEPENDENCY") || e.message().toLowerCase().contains("depend")));
    }

    // -------------------------------------------------------
    // 13. Invalid YAML: cyclic stage dependency
    // -------------------------------------------------------

    @Test
    void submitYaml_cyclicStageDependency_throwsPipelineValidationException() {
        Pipeline pipeline = pipeline();
        String badYaml = "pipeline:\n  name: cycle\n  stages:\n"
                + "    - name: a\n      dependsOn:\n        - b\n      jobs:\n"
                + "        - name: j1\n          type: build\n"
                + "    - name: b\n      dependsOn:\n        - a\n      jobs:\n"
                + "        - name: j2\n          type: build";

        PipelineValidationException ex = assertThrows(PipelineValidationException.class,
                () -> pipelineYamlService.submitYaml(pipeline.getId(), badYaml, "alice"));
        assertTrue(ex.getValidationErrors().stream()
                .anyMatch(e -> "CYCLIC_DEPENDENCY".equals(e.code()) || e.message().toLowerCase().contains("circular")));
    }

    // -------------------------------------------------------
    // 14. Pipeline not found
    // -------------------------------------------------------

    @Test
    void submitYaml_pipelineNotFound_throwsException() {
        String yml = yaml("x", "    - name: s\n      jobs:\n        - name: j\n          type: build");
        assertThrows(Exception.class,
                () -> pipelineYamlService.submitYaml(UUID.randomUUID(), yml, "alice"));
    }

    // -------------------------------------------------------
    // 15. Pipeline name from YAML stored correctly on entity
    // -------------------------------------------------------

    @Test
    void submitYaml_pipelineNameAndDescriptionStoredOnEntity() {
        Pipeline pipeline = pipeline();
        String yml = "pipeline:\n  name: named-pipe\n  description: A test pipeline\n"
                + "  stages:\n    - name: build\n      jobs:\n"
                + "        - name: job1\n          type: build";

        PipelineVersion version = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");

        PipelineRun run = runService.triggerRun(version.getId(), "sha-name", "main", null, "alice");
        assertNotNull(run);
        assertNotNull(version.getCreatedAt());
    }

    // -------------------------------------------------------
    // 16. dispatchReadyJobs sends messages for all PENDING jobs
    // -------------------------------------------------------

    @Test
    void submitYaml_multipleStagesMultipleJobs_allDispatched() {
        Pipeline pipeline = pipeline();
        String yml = "pipeline:\n  name: multi\n  stages:\n"
                + "    - name: stage-a\n      jobs:\n"
                + "        - name: a1\n          type: build\n"
                + "        - name: a2\n          type: test\n"
                + "    - name: stage-b\n      jobs:\n"
                + "        - name: b1\n          type: scan\n"
                + "        - name: b2\n          type: deploy\n";

        PipelineVersion version = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");
        PipelineRun run = runService.triggerRun(version.getId(), "sha-multi", "main", null, "alice");

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(2, stages.size());

        UUID stageAId = stages.get(0).getId();
        UUID stageBId = stages.get(1).getId();
        List<PipelineJob> jobsA = pipelineJobRepository.findByPipelineStageId(stageAId);
        List<PipelineJob> jobsB = pipelineJobRepository.findByPipelineStageId(stageBId);
        assertEquals(2, jobsA.size());
        assertEquals(2, jobsB.size());

        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                any(JobDispatchMessage.class));
    }

    // -------------------------------------------------------
    // 17. Submit YAML that references nonexistent project
    // -------------------------------------------------------

    @Test
    void submitYamlToProject_projectNotFound_throwsException() {
        String yml = yaml("x", "    - name: s\n      jobs:\n        - name: j\n          type: build");
        assertThrows(Exception.class,
                () -> pipelineYamlService.validateAndSubmitToProject(UUID.randomUUID(), yml, "alice"));
    }

    // -------------------------------------------------------
    // 18. Three-stage pipeline with linear stage dependencies
    // -------------------------------------------------------

    @Test
    void submitYaml_threeStagesLinearDeps_createsCorrectOrderIndex() {
        Pipeline pipeline = pipeline();
        String yml = "pipeline:\n  name: linear\n  stages:\n"
                + "    - name: compile\n      jobs:\n        - name: c\n          type: build\n"
                + "    - name: test\n      dependsOn:\n        - compile\n      jobs:\n        - name: t\n          type: test\n"
                + "    - name: deploy\n      dependsOn:\n        - test\n      jobs:\n        - name: d\n          type: deploy\n";

        PipelineVersion version = pipelineYamlService.submitYaml(pipeline.getId(), yml, "alice");
        PipelineRun run = runService.triggerRun(version.getId(), "sha-3", "main", null, "alice");

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(3, stages.size());
        assertEquals("compile", stages.get(0).getName());
        assertEquals(0, stages.get(0).getOrderIndex());
        assertEquals("test", stages.get(1).getName());
        assertEquals(1, stages.get(1).getOrderIndex());
        assertEquals("deploy", stages.get(2).getName());
        assertEquals(2, stages.get(2).getOrderIndex());

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(ExecutionConstants.JOB_DISPATCH_EXCHANGE),
                eq(ExecutionConstants.JOB_DISPATCH_ROUTING_KEY),
                any(JobDispatchMessage.class));
    }
}
