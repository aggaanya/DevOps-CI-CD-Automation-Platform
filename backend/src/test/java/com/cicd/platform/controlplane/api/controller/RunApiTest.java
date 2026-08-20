package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.cicd.platform.controlplane.execution.config.ExecutionConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private PipelineRunRepository pipelineRunRepository;
    @Autowired private PipelineStageRepository pipelineStageRepository;
    @Autowired private PipelineJobRepository pipelineJobRepository;
    @Autowired private JobAttemptRepository jobAttemptRepository;

    @MockBean private RabbitTemplate rabbitTemplate;

    private PipelineVersion version;

    private static final String TWO_STAGE_YAML =
            "pipeline:\n"
            + "  name: test-pipeline\n"
            + "  stages:\n"
            + "    - name: build\n"
            + "      jobs:\n"
            + "        - name: compile\n"
            + "          type: build\n"
            + "    - name: test\n"
            + "      jobs:\n"
            + "        - name: unit-test\n"
            + "          type: test\n";

    @BeforeEach
    void setUp() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        Organization org = organizationRepository.save(
                new Organization("api-org-" + uid, "api-org-" + uid, "desc"));
        Project project = projectRepository.save(
                new Project(org, "api-proj-" + uid, "api-proj-" + uid, "desc"));
        Pipeline pipeline = pipelineRepository.save(
                new Pipeline(project, "api-pipe-" + uid, "desc"));
        version = pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, TWO_STAGE_YAML, "sha-init", "setup"));
    }

    @Test
    void postRuns_validRequest_createsRunAndDispatchesJobs() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "pipelineVersionId", version.getId().toString(),
                "commitSha", "abc123",
                "branch", "main",
                "triggeredBy", "alice"));

        String body = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.pipelineVersionId").value(version.getId().toString()))
                .andExpect(jsonPath("$.commitSha").value("abc123"))
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.triggerType").value("API"))
                .andExpect(jsonPath("$.triggeredBy").value("alice"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn().getResponse().getContentAsString();

        UUID runId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        // 1. PipelineRun exists
        PipelineRun run = pipelineRunRepository.findById(runId).orElseThrow();
        // 2. Expected pipeline version
        assertEquals(version.getId(), run.getPipelineVersion().getId());
        // 3. Expected branch
        assertEquals("main", run.getBranch());
        // 4. Expected commit SHA
        assertEquals("abc123", run.getCommitSha());
        // 5. Status is RUNNING
        assertEquals(PipelineRun.RunStatus.RUNNING, run.getStatus());
        assertNotNull(run.getStartedAt());

        // 6. PipelineStage records were created
        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(runId);
        assertEquals(2, stages.size());
        assertEquals("build", stages.get(0).getName());
        assertEquals(0, stages.get(0).getOrderIndex());
        assertEquals("test", stages.get(1).getName());
        assertEquals(1, stages.get(1).getOrderIndex());

        // 7. PipelineJob records were created
        List<PipelineJob> buildJobs = pipelineJobRepository
                .findByPipelineStageId(stages.get(0).getId());
        List<PipelineJob> testJobs = pipelineJobRepository
                .findByPipelineStageId(stages.get(1).getId());
        assertEquals(1, buildJobs.size());
        assertEquals(1, testJobs.size());
        assertEquals("compile", buildJobs.get(0).getName());
        assertEquals(PipelineJob.JobType.BUILD, buildJobs.get(0).getJobType());
        assertEquals("unit-test", testJobs.get(0).getName());
        assertEquals(PipelineJob.JobType.TEST, testJobs.get(0).getJobType());

        // 8. First eligible jobs moved to QUEUED, gated jobs stay PENDING
        assertEquals(PipelineJob.JobStatus.QUEUED, buildJobs.get(0).getStatus());
        assertEquals(PipelineJob.JobStatus.PENDING, testJobs.get(0).getStatus());

        // 9. JobAttempt created for dispatched jobs
        List<JobAttempt> buildAttempts = jobAttemptRepository
                .findByJobIdOrderByAttemptNumberAsc(buildJobs.get(0).getId());
        assertEquals(1, buildAttempts.size());
        assertEquals(1, buildAttempts.get(0).getAttemptNumber());

        List<JobAttempt> testAttempts = jobAttemptRepository
                .findByJobIdOrderByAttemptNumberAsc(testJobs.get(0).getId());
        assertEquals(0, testAttempts.size());

        // 10. RabbitTemplate.convertAndSend called for dispatched jobs
        ArgumentCaptor<JobDispatchMessage> captor =
                ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE),
                eq(JOB_DISPATCH_ROUTING_KEY),
                captor.capture());
        JobDispatchMessage message = captor.getValue();
        assertEquals(buildJobs.get(0).getId(), message.jobId());
        assertEquals(runId, message.runId());
        assertEquals(version.getId(), message.pipelineVersionId());
    }
}
