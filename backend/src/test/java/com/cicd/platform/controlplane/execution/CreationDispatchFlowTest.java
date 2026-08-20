package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import com.cicd.platform.controlplane.execution.config.ExecutionConstants;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static com.cicd.platform.controlplane.execution.config.ExecutionConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class CreationDispatchFlowTest {

    @Autowired private RunService runService;
    @Autowired private PipelineRunRepository pipelineRunRepository;
    @Autowired private PipelineStageRepository pipelineStageRepository;
    @Autowired private PipelineJobRepository pipelineJobRepository;
    @Autowired private JobAttemptRepository jobAttemptRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;

    @MockBean private RabbitTemplate rabbitTemplate;

    private PipelineVersion createVersionWithYaml(String name, String yaml) {
        Organization org = organizationRepository.save(
                new Organization(name + "-org", name + "-org-slug", "desc"));
        Project project = projectRepository.save(
                new Project(org, name + "-proj", name + "-proj-slug", "desc"));
        Pipeline pipeline = pipelineRepository.save(
                new Pipeline(project, name + "-pipe", "desc"));
        return pipelineVersionRepository.save(
                new PipelineVersion(pipeline, 1, yaml, null, "test-user"));
    }

    @Test
    void yamlToMessage_jobIdMatchesPersistedEntity() {
        String yaml = "pipeline:\n  name: test-pipeline\n  stages:\n"
                + "    - name: build\n      jobs:\n"
                + "        - name: build-job\n          type: build";
        PipelineVersion version = createVersionWithYaml("flow1", yaml);

        PipelineRun run = runService.triggerRun(
                version.getId(), "commit-sha-123", "main", null, "alice");

        assertNotNull(run.getId());
        assertEquals(PipelineRun.RunStatus.RUNNING, run.getStatus());

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(1, stages.size());
        assertNotNull(stages.get(0).getId());
        assertEquals("build", stages.get(0).getName());

        List<PipelineJob> jobs = pipelineJobRepository
                .findByPipelineStageId(stages.get(0).getId());
        assertEquals(1, jobs.size());
        PipelineJob job = jobs.get(0);
        assertNotNull(job.getId());
        assertEquals("build-job", job.getName());
        assertEquals(PipelineJob.JobType.BUILD, job.getJobType());
        assertEquals(PipelineJob.JobStatus.QUEUED, job.getStatus());

        ArgumentCaptor<JobDispatchMessage> captor =
                ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE),
                eq(JOB_DISPATCH_ROUTING_KEY),
                captor.capture());

        JobDispatchMessage message = captor.getValue();
        assertEquals(job.getId(), message.jobId(),
                "jobId in message must match persisted PipelineJob UUID");
        assertEquals(run.getId(), message.runId());
        assertEquals(version.getId(), message.pipelineVersionId());
        assertEquals("build-job", message.jobName());
        assertEquals("BUILD", message.jobType());
    }

    @Test
    void yamlToMessage_multipleJobsEachGetUniqueUuid() {
        String yaml = "pipeline:\n  name: multi-job\n  stages:\n"
                + "    - name: build\n      jobs:\n"
                + "        - name: job-a\n          type: build\n"
                + "        - name: job-b\n          type: test";
        PipelineVersion version = createVersionWithYaml("flow2", yaml);

        PipelineRun run = runService.triggerRun(
                version.getId(), "sha-abc", "main", null, "alice");

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(1, stages.size());

        List<PipelineJob> jobs = pipelineJobRepository
                .findByPipelineStageId(stages.get(0).getId());
        assertEquals(2, jobs.size());

        UUID id0 = jobs.get(0).getId();
        UUID id1 = jobs.get(1).getId();
        assertNotNull(id0);
        assertNotNull(id1);
        assertNotEquals(id0, id1);

        assertEquals(PipelineJob.JobStatus.QUEUED, jobs.get(0).getStatus());
        assertEquals(PipelineJob.JobStatus.QUEUED, jobs.get(1).getStatus());

        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE),
                eq(JOB_DISPATCH_ROUTING_KEY),
                any(JobDispatchMessage.class));
    }

    @Test
    void yamlToMessage_attemptCreatedBeforeMessageDispatched() {
        String yaml = "pipeline:\n  name: attempt-test\n  stages:\n"
                + "    - name: build\n      jobs:\n"
                + "        - name: build-job\n          type: build";
        PipelineVersion version = createVersionWithYaml("flow3", yaml);

        PipelineRun run = runService.triggerRun(
                version.getId(), "sha-xyz", "main", null, "alice");

        List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(
                pipelineStageRepository
                        .findByPipelineRunIdOrderByOrderIndexAsc(run.getId())
                        .get(0).getId());
        PipelineJob job = jobs.get(0);

        List<JobAttempt> attempts = jobAttemptRepository
                .findByJobIdOrderByAttemptNumberAsc(job.getId());
        assertEquals(1, attempts.size());
        assertNotNull(attempts.get(0).getId());
        assertEquals(1, attempts.get(0).getAttemptNumber());

        ArgumentCaptor<JobDispatchMessage> captor =
                ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE),
                eq(JOB_DISPATCH_ROUTING_KEY),
                captor.capture());
        assertEquals(1, captor.getValue().attemptNumber());
    }

    @Test
    void yamlToMessage_stagesPreserveOrderIndex() {
        String yaml = "pipeline:\n  name: ordered\n  stages:\n"
                + "    - name: first\n      jobs:\n"
                + "        - name: job1\n          type: build\n"
                + "    - name: second\n      jobs:\n"
                + "        - name: job2\n          type: test";
        PipelineVersion version = createVersionWithYaml("flow4", yaml);

        PipelineRun run = runService.triggerRun(
                version.getId(), "sha-ord", "main", null, "alice");

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(run.getId());
        assertEquals(2, stages.size());
        assertEquals("first", stages.get(0).getName());
        assertEquals(0, stages.get(0).getOrderIndex());
        assertEquals("second", stages.get(1).getName());
        assertEquals(1, stages.get(1).getOrderIndex());

        List<PipelineJob> jobs1 = pipelineJobRepository
                .findByPipelineStageId(stages.get(0).getId());
        List<PipelineJob> jobs2 = pipelineJobRepository
                .findByPipelineStageId(stages.get(1).getId());
        assertEquals(1, jobs1.size());
        assertEquals(1, jobs2.size());

        assertEquals("job1", jobs1.get(0).getName());
        assertEquals("job2", jobs2.get(0).getName());
        assertNotEquals(jobs1.get(0).getId(), jobs2.get(0).getId());
    }
}
