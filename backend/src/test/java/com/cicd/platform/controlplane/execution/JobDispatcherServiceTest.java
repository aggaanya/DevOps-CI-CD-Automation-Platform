package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.repository.JobAttemptRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cicd.platform.controlplane.execution.config.ExecutionConstants.JOB_DISPATCH_EXCHANGE;
import static com.cicd.platform.controlplane.execution.config.ExecutionConstants.JOB_DISPATCH_ROUTING_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobDispatcherServiceTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private PipelineJobRepository pipelineJobRepository;
    @Mock private JobAttemptRepository jobAttemptRepository;
    @Mock private WorkspaceConfig workspaceConfig;
    @InjectMocks private JobDispatcherService dispatcher;

    @Test
    void dispatchReadyJobs_dispatchesPendingJobs() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage stage = new PipelineStage();
        stage.setStatus(PipelineStage.StageStatus.PENDING);
        stage.setOrderIndex(0);
        stage.setPipelineRun(run);

        PipelineJob job = new PipelineJob();
        job.setStatus(PipelineJob.JobStatus.PENDING);
        job.setName("build");
        job.setJobType(PipelineJob.JobType.BUILD);
        job.setPipelineStage(stage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stage.getId()))
                .thenReturn(List.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, job.getStatus());
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchJob_createsAttemptAndSendsMessage() {
        PipelineJob job = new PipelineJob();
        job.setName("test");
        job.setJobType(PipelineJob.JobType.TEST);

        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setBranch("main");
        run.setCommitSha("abc123");

        PipelineStage stage = new PipelineStage();
        stage.setPipelineRun(run);
        job.setPipelineStage(stage);
        run.setPipelineVersion(version);

        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchJob(job);

        assertEquals(PipelineJob.JobStatus.QUEUED, job.getStatus());
        verify(jobAttemptRepository).save(any(JobAttempt.class));
        ArgumentCaptor<JobDispatchMessage> captor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), captor.capture());
        assertEquals("test", captor.getValue().jobName());
        assertEquals(1, captor.getValue().attemptNumber());
    }

    @Test
    void dispatchJob_computesAttemptNumberPerJob() {
        PipelineJob job = new PipelineJob();
        job.setName("test");
        job.setJobType(PipelineJob.JobType.TEST);

        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setBranch("main");
        run.setCommitSha("abc123");

        PipelineStage stage = new PipelineStage();
        stage.setPipelineRun(run);
        job.setPipelineStage(stage);
        run.setPipelineVersion(version);

        JobAttempt existingAttempt = new JobAttempt(job, 2);
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of(existingAttempt));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchJob(job);

        ArgumentCaptor<JobAttempt> attemptCaptor = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository).save(attemptCaptor.capture());
        assertEquals(3, attemptCaptor.getValue().getAttemptNumber());
        ArgumentCaptor<JobDispatchMessage> msgCaptor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), msgCaptor.capture());
        assertEquals(3, msgCaptor.getValue().attemptNumber());
    }

    @Test
    void dispatchForRetry_createsAttemptWithCorrectNumber() {
        PipelineJob job = new PipelineJob();
        job.setName("deploy");
        job.setJobType(PipelineJob.JobType.DEPLOY);

        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setBranch("main");
        run.setCommitSha("def456");

        PipelineStage stage = new PipelineStage();
        stage.setPipelineRun(run);
        job.setPipelineStage(stage);
        run.setPipelineVersion(version);

        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchForRetry(job, 3);

        assertEquals(PipelineJob.JobStatus.QUEUED, job.getStatus());
        ArgumentCaptor<JobAttempt> attemptCaptor = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository).save(attemptCaptor.capture());
        assertEquals(3, attemptCaptor.getValue().getAttemptNumber());
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchJob_messageJobIdMatchesEntityId() throws Exception {
        UUID jobUuid = UUID.randomUUID();
        PipelineJob job = new PipelineJob();
        job.setName("test");
        job.setJobType(PipelineJob.JobType.TEST);
        setId(job, jobUuid);

        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setBranch("main");
        run.setCommitSha("abc123");

        PipelineStage stage = new PipelineStage();
        stage.setPipelineRun(run);
        job.setPipelineStage(stage);
        run.setPipelineVersion(version);

        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobUuid))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchJob(job);

        ArgumentCaptor<JobDispatchMessage> captor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), captor.capture());
        assertEquals(jobUuid, captor.getValue().jobId(),
                "Message jobId must match the persisted entity UUID");
    }

    @Test
    void dispatchJob_messageRunIdMatchesEntityChain() throws Exception {
        UUID jobUuid = UUID.randomUUID();
        UUID runUuid = UUID.randomUUID();
        UUID versionUuid = UUID.randomUUID();

        PipelineJob job = new PipelineJob();
        job.setName("build");
        job.setJobType(PipelineJob.JobType.BUILD);
        setId(job, jobUuid);

        PipelineVersion version = new PipelineVersion();
        setId(version, versionUuid);

        PipelineRun run = new PipelineRun();
        setId(run, runUuid);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage stage = new PipelineStage();
        stage.setPipelineRun(run);
        job.setPipelineStage(stage);

        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobUuid))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchJob(job);

        ArgumentCaptor<JobDispatchMessage> captor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), captor.capture());
        assertEquals(jobUuid, captor.getValue().jobId());
        assertEquals(runUuid, captor.getValue().runId());
        assertEquals(versionUuid, captor.getValue().pipelineVersionId());
    }

    @Test
    void dispatchReadyJobs_skipsWhenRunNotFound() {
        UUID runId = UUID.randomUUID();
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.empty());

        dispatcher.dispatchReadyJobs(runId);

        verify(pipelineStageRepository, never()).findByPipelineRunIdOrderByOrderIndexAsc(any());
        verify(rabbitTemplate, never()).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_skipsWhenRunNotRunning() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.FAILED);
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        dispatcher.dispatchReadyJobs(runId);

        verify(pipelineStageRepository, never()).findByPipelineRunIdOrderByOrderIndexAsc(any());
        verify(rabbitTemplate, never()).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_failedStageBlocksDownstream() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        UUID stage0Id = UUID.randomUUID();
        PipelineStage stage0 = new PipelineStage();
        setId(stage0, stage0Id);
        stage0.setStatus(PipelineStage.StageStatus.FAILED);
        stage0.setOrderIndex(0);
        stage0.setPipelineRun(run);

        UUID stage1Id = UUID.randomUUID();
        PipelineStage stage1 = new PipelineStage();
        setId(stage1, stage1Id);
        stage1.setStatus(PipelineStage.StageStatus.PENDING);
        stage1.setOrderIndex(1);
        stage1.setPipelineRun(run);

        PipelineJob downstreamJob = new PipelineJob();
        downstreamJob.setStatus(PipelineJob.JobStatus.PENDING);
        downstreamJob.setName("deploy");
        downstreamJob.setJobType(PipelineJob.JobType.DEPLOY);
        downstreamJob.setPipelineStage(stage1);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage0, stage1));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.PENDING, downstreamJob.getStatus());
        verify(rabbitTemplate, never()).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_previousStageNotSuccess_blocksDownstream() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        UUID stage0Id = UUID.randomUUID();
        PipelineStage stage0 = new PipelineStage();
        setId(stage0, stage0Id);
        stage0.setStatus(PipelineStage.StageStatus.RUNNING);
        stage0.setOrderIndex(0);
        stage0.setPipelineRun(run);

        UUID stage1Id = UUID.randomUUID();
        PipelineStage stage1 = new PipelineStage();
        setId(stage1, stage1Id);
        stage1.setStatus(PipelineStage.StageStatus.PENDING);
        stage1.setOrderIndex(1);
        stage1.setPipelineRun(run);

        PipelineJob downstreamJob = new PipelineJob();
        downstreamJob.setStatus(PipelineJob.JobStatus.PENDING);
        downstreamJob.setName("test");
        downstreamJob.setJobType(PipelineJob.JobType.TEST);
        downstreamJob.setPipelineStage(stage1);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage0, stage1));
        when(pipelineJobRepository.findByPipelineStageId(stage0Id))
                .thenReturn(List.of());

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.PENDING, downstreamJob.getStatus());
        verify(rabbitTemplate, never()).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_successfulStageUnlocksNext() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage stage0 = new PipelineStage();
        stage0.setStatus(PipelineStage.StageStatus.SUCCESS);
        stage0.setOrderIndex(0);
        stage0.setPipelineRun(run);

        PipelineStage stage1 = new PipelineStage();
        stage1.setStatus(PipelineStage.StageStatus.PENDING);
        stage1.setOrderIndex(1);
        stage1.setPipelineRun(run);

        PipelineJob job = new PipelineJob();
        job.setStatus(PipelineJob.JobStatus.PENDING);
        job.setName("test");
        job.setJobType(PipelineJob.JobType.TEST);
        job.setPipelineStage(stage1);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage0, stage1));
        when(pipelineJobRepository.findByPipelineStageId(stage1.getId()))
                .thenReturn(List.of(job));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, job.getStatus());
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_dependsOnBlocksWhileDependencyRunning() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(DEPLOY_DEPENDS_ON_SECURITY_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        UUID securityStageId = UUID.randomUUID();
        PipelineStage securityStage = new PipelineStage();
        setId(securityStage, securityStageId);
        securityStage.setName("security");
        securityStage.setStatus(PipelineStage.StageStatus.RUNNING);
        securityStage.setOrderIndex(0);
        securityStage.setPipelineRun(run);

        UUID deployStageId = UUID.randomUUID();
        PipelineStage deployStage = new PipelineStage();
        setId(deployStage, deployStageId);
        deployStage.setName("deploy");
        deployStage.setStatus(PipelineStage.StageStatus.PENDING);
        deployStage.setOrderIndex(1);
        deployStage.setPipelineRun(run);

        PipelineJob deployJob = new PipelineJob();
        deployJob.setStatus(PipelineJob.JobStatus.PENDING);
        deployJob.setName("publish");
        deployJob.setJobType(PipelineJob.JobType.DEPLOY);
        deployJob.setPipelineStage(deployStage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(securityStage, deployStage));
        lenient().when(pipelineJobRepository.findByPipelineStageId(securityStageId))
                .thenReturn(List.of());

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.PENDING, deployJob.getStatus(),
                "deploy should stay PENDING because security dependency is RUNNING, not SUCCESS");
        verify(rabbitTemplate, never()).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_dependsOnDispatchesWhenDependencySucceeds() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(DEPLOY_DEPENDS_ON_SECURITY_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage securityStage = new PipelineStage();
        securityStage.setName("security");
        securityStage.setStatus(PipelineStage.StageStatus.SUCCESS);
        securityStage.setOrderIndex(0);
        securityStage.setPipelineRun(run);

        PipelineStage deployStage = new PipelineStage();
        deployStage.setName("deploy");
        deployStage.setStatus(PipelineStage.StageStatus.PENDING);
        deployStage.setOrderIndex(1);
        deployStage.setPipelineRun(run);

        PipelineJob deployJob = new PipelineJob();
        deployJob.setStatus(PipelineJob.JobStatus.PENDING);
        deployJob.setName("publish");
        deployJob.setJobType(PipelineJob.JobType.DEPLOY);
        deployJob.setPipelineStage(deployStage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(securityStage, deployStage));
        when(pipelineJobRepository.findByPipelineStageId(deployStage.getId()))
                .thenReturn(List.of(deployJob));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(deployJob.getId()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, deployJob.getStatus(),
                "deploy should be dispatched because security dependency is SUCCESS");
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_dependsOnBlocksIfEitherDependencyNotSuccess() throws Exception {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(DEPLOY_DEPENDS_ON_BUILD_AND_SECURITY_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        UUID buildStageId = UUID.randomUUID();
        PipelineStage buildStage = new PipelineStage();
        setId(buildStage, buildStageId);
        buildStage.setName("build");
        buildStage.setStatus(PipelineStage.StageStatus.SUCCESS);
        buildStage.setOrderIndex(0);
        buildStage.setPipelineRun(run);

        UUID securityStageId = UUID.randomUUID();
        PipelineStage securityStage = new PipelineStage();
        setId(securityStage, securityStageId);
        securityStage.setName("security");
        securityStage.setStatus(PipelineStage.StageStatus.RUNNING);
        securityStage.setOrderIndex(1);
        securityStage.setPipelineRun(run);

        UUID deployStageId = UUID.randomUUID();
        PipelineStage deployStage = new PipelineStage();
        setId(deployStage, deployStageId);
        deployStage.setName("deploy");
        deployStage.setStatus(PipelineStage.StageStatus.PENDING);
        deployStage.setOrderIndex(2);
        deployStage.setPipelineRun(run);

        PipelineJob deployJob = new PipelineJob();
        deployJob.setStatus(PipelineJob.JobStatus.PENDING);
        deployJob.setName("publish");
        deployJob.setJobType(PipelineJob.JobType.DEPLOY);
        deployJob.setPipelineStage(deployStage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(buildStage, securityStage, deployStage));
        lenient().when(pipelineJobRepository.findByPipelineStageId(securityStageId))
                .thenReturn(List.of());

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.PENDING, deployJob.getStatus(),
                "deploy should stay PENDING because security (second dependency) is not SUCCESS");
        verify(rabbitTemplate, never()).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_dependsOnIgnoresUnrelatedEarlierStageFailure() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(DEPLOY_DEPENDS_ON_SECURITY_THREE_STAGE_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage buildStage = new PipelineStage();
        buildStage.setName("build");
        buildStage.setStatus(PipelineStage.StageStatus.FAILED);
        buildStage.setOrderIndex(0);
        buildStage.setPipelineRun(run);

        PipelineStage securityStage = new PipelineStage();
        securityStage.setName("security");
        securityStage.setStatus(PipelineStage.StageStatus.SUCCESS);
        securityStage.setOrderIndex(1);
        securityStage.setPipelineRun(run);

        PipelineStage deployStage = new PipelineStage();
        deployStage.setName("deploy");
        deployStage.setStatus(PipelineStage.StageStatus.PENDING);
        deployStage.setOrderIndex(2);
        deployStage.setPipelineRun(run);

        PipelineJob deployJob = new PipelineJob();
        deployJob.setStatus(PipelineJob.JobStatus.PENDING);
        deployJob.setName("publish");
        deployJob.setJobType(PipelineJob.JobType.DEPLOY);
        deployJob.setPipelineStage(deployStage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(buildStage, securityStage, deployStage));
        when(pipelineJobRepository.findByPipelineStageId(deployStage.getId()))
                .thenReturn(List.of(deployJob));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(deployJob.getId()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, deployJob.getStatus(),
                "deploy depends on security (not build), so build failure should not block deploy");
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_noDependsOn_usesPositionalOrdering() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(NO_DEPENDS_ON_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage buildStage = new PipelineStage();
        buildStage.setName("build");
        buildStage.setStatus(PipelineStage.StageStatus.SUCCESS);
        buildStage.setOrderIndex(0);
        buildStage.setPipelineRun(run);

        PipelineStage testStage = new PipelineStage();
        testStage.setName("test");
        testStage.setStatus(PipelineStage.StageStatus.PENDING);
        testStage.setOrderIndex(1);
        testStage.setPipelineRun(run);

        PipelineJob testJob = new PipelineJob();
        testJob.setStatus(PipelineJob.JobStatus.PENDING);
        testJob.setName("unit-test");
        testJob.setJobType(PipelineJob.JobType.TEST);
        testJob.setPipelineStage(testStage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(buildStage, testStage));
        when(pipelineJobRepository.findByPipelineStageId(testStage.getId()))
                .thenReturn(List.of(testJob));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(testJob.getId()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, testJob.getStatus(),
                "Without dependsOn, positional ordering should dispatch test after build succeeds");
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    // -------------------------------------------------------
    // Job-level dependsOn enforcement tests
    // -------------------------------------------------------

    @Test
    void dispatchReadyJobs_jobDependsOn_blocksWhileDependencyPending() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(JOB_DEP_WITHIN_STAGE_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage stage = new PipelineStage();
        stage.setName("build");
        stage.setStatus(PipelineStage.StageStatus.PENDING);
        stage.setOrderIndex(0);
        stage.setPipelineRun(run);

        PipelineJob compileJob = new PipelineJob();
        compileJob.setName("compile");
        compileJob.setJobType(PipelineJob.JobType.BUILD);
        compileJob.setStatus(PipelineJob.JobStatus.PENDING);
        compileJob.setPipelineStage(stage);

        PipelineJob testJob = new PipelineJob();
        testJob.setName("unit-test");
        testJob.setJobType(PipelineJob.JobType.TEST);
        testJob.setStatus(PipelineJob.JobStatus.PENDING);
        testJob.setPipelineStage(stage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stage.getId()))
                .thenReturn(List.of(compileJob, testJob));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(any()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, compileJob.getStatus(),
                "compile has no deps, should be dispatched");
        assertEquals(PipelineJob.JobStatus.PENDING, testJob.getStatus(),
                "unit-test depends on compile which is not SUCCESS yet, should stay PENDING");
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_jobDependsOn_blocksWhileDependencyRunning() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(JOB_DEP_WITHIN_STAGE_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage stage = new PipelineStage();
        stage.setName("build");
        stage.setStatus(PipelineStage.StageStatus.PENDING);
        stage.setOrderIndex(0);
        stage.setPipelineRun(run);

        PipelineJob compileJob = new PipelineJob();
        compileJob.setName("compile");
        compileJob.setJobType(PipelineJob.JobType.BUILD);
        compileJob.setStatus(PipelineJob.JobStatus.RUNNING);
        compileJob.setPipelineStage(stage);

        PipelineJob testJob = new PipelineJob();
        testJob.setName("unit-test");
        testJob.setJobType(PipelineJob.JobType.TEST);
        testJob.setStatus(PipelineJob.JobStatus.PENDING);
        testJob.setPipelineStage(stage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stage.getId()))
                .thenReturn(List.of(compileJob, testJob));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.RUNNING, compileJob.getStatus(),
                "compile is already running, not PENDING, so not dispatched");
        assertEquals(PipelineJob.JobStatus.PENDING, testJob.getStatus(),
                "unit-test depends on compile which is RUNNING, should stay PENDING");
        verify(rabbitTemplate, never()).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_jobDependsOn_dispatchesWhenDependencySuccess() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(JOB_DEP_WITHIN_STAGE_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage stage = new PipelineStage();
        stage.setName("build");
        stage.setStatus(PipelineStage.StageStatus.PENDING);
        stage.setOrderIndex(0);
        stage.setPipelineRun(run);

        PipelineJob compileJob = new PipelineJob();
        compileJob.setName("compile");
        compileJob.setJobType(PipelineJob.JobType.BUILD);
        compileJob.setStatus(PipelineJob.JobStatus.SUCCESS);
        compileJob.setPipelineStage(stage);

        PipelineJob testJob = new PipelineJob();
        testJob.setName("unit-test");
        testJob.setJobType(PipelineJob.JobType.TEST);
        testJob.setStatus(PipelineJob.JobStatus.PENDING);
        testJob.setPipelineStage(stage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stage.getId()))
                .thenReturn(List.of(compileJob, testJob));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(any()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, testJob.getStatus(),
                "unit-test depends on compile which is SUCCESS, should be dispatched");
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    @Test
    void dispatchReadyJobs_multipleJobDeps_allMustBeSuccess() {
        UUID runId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(MULTI_JOB_DEP_WITHIN_STAGE_YAML);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);

        PipelineStage stage = new PipelineStage();
        stage.setName("build");
        stage.setStatus(PipelineStage.StageStatus.PENDING);
        stage.setOrderIndex(0);
        stage.setPipelineRun(run);

        PipelineJob compileJob = new PipelineJob();
        compileJob.setName("compile");
        compileJob.setJobType(PipelineJob.JobType.BUILD);
        compileJob.setStatus(PipelineJob.JobStatus.PENDING);
        compileJob.setPipelineStage(stage);

        PipelineJob lintJob = new PipelineJob();
        lintJob.setName("lint");
        lintJob.setJobType(PipelineJob.JobType.CUSTOM);
        lintJob.setStatus(PipelineJob.JobStatus.PENDING);
        lintJob.setPipelineStage(stage);

        PipelineJob testJob = new PipelineJob();
        testJob.setName("unit-test");
        testJob.setJobType(PipelineJob.JobType.TEST);
        testJob.setStatus(PipelineJob.JobStatus.PENDING);
        testJob.setPipelineStage(stage);

        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId))
                .thenReturn(List.of(stage));
        when(pipelineJobRepository.findByPipelineStageId(stage.getId()))
                .thenReturn(List.of(compileJob, lintJob, testJob));
        when(pipelineJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(any()))
                .thenReturn(List.of());
        when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatchReadyJobs(runId);

        assertEquals(PipelineJob.JobStatus.QUEUED, compileJob.getStatus(),
                "compile has no deps, should be dispatched");
        assertEquals(PipelineJob.JobStatus.QUEUED, lintJob.getStatus(),
                "lint has no deps, should be dispatched");
        assertEquals(PipelineJob.JobStatus.PENDING, testJob.getStatus(),
                "unit-test depends on compile and lint, neither is SUCCESS yet, should stay PENDING");
        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    private static final String JOB_DEP_WITHIN_STAGE_YAML = """
            pipeline:
              name: job-dep-test
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                    - name: unit-test
                      type: TEST
                      dependsOn:
                        - compile
            """;

    private static final String MULTI_JOB_DEP_WITHIN_STAGE_YAML = """
            pipeline:
              name: multi-job-dep-test
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                    - name: lint
                      type: CUSTOM
                    - name: unit-test
                      type: TEST
                      dependsOn:
                        - compile
                        - lint
            """;

    private static final String DEPLOY_DEPENDS_ON_SECURITY_YAML = """
            pipeline:
              name: test-pipeline
              stages:
                - name: security
                  jobs:
                    - name: scan
                      type: SCAN
                - name: deploy
                  dependsOn:
                    - security
                  jobs:
                    - name: publish
                      type: DEPLOY
            """;

    private static final String DEPLOY_DEPENDS_ON_BUILD_AND_SECURITY_YAML = """
            pipeline:
              name: test-pipeline
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                - name: security
                  jobs:
                    - name: scan
                      type: SCAN
                - name: deploy
                  dependsOn:
                    - build
                    - security
                  jobs:
                    - name: publish
                      type: DEPLOY
            """;

    private static final String NO_DEPENDS_ON_YAML = """
            pipeline:
              name: test-pipeline
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                - name: test
                  jobs:
                    - name: unit-test
                      type: TEST
            """;

    private static final String DEPLOY_DEPENDS_ON_SECURITY_THREE_STAGE_YAML = """
            pipeline:
              name: test-pipeline
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                - name: security
                  jobs:
                    - name: scan
                      type: SCAN
                - name: deploy
                  dependsOn:
                    - security
                  jobs:
                    - name: publish
                      type: DEPLOY
            """;

    private static void setId(Object entity, UUID id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
