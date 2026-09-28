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
import com.cicd.platform.controlplane.execution.message.JobDispatchMessage;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.cicd.platform.controlplane.execution.config.ExecutionConstants.JOB_DISPATCH_EXCHANGE;
import static com.cicd.platform.controlplane.execution.config.ExecutionConstants.JOB_DISPATCH_ROUTING_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the DAG-driven dispatch pass.
 *
 * <p><b>How the repository is faked.</b> The dispatcher no longer mutates job
 * status through {@code save}; it issues a conditional
 * {@code claimForDispatch} UPDATE and treats the returned row count as the
 * duplicate-execution guard. {@link #stubClaims} therefore emulates the database
 * rather than JPA: a claim succeeds only when the job is currently in one of the
 * expected statuses, and it moves the job to the target status. A stub that
 * returned 1 unconditionally would make the tests pass while proving nothing
 * about the guard, which is the property under test here.
 */
@ExtendWith(MockitoExtension.class)
class JobDispatcherServiceTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private PipelineJobRepository pipelineJobRepository;
    @Mock private JobAttemptRepository jobAttemptRepository;
    @Mock private RunSchedulerLockRegistry runLocks;
    @Mock private RunStateSettler runStateSettler;
    @Mock private OutboxEventService outboxEventService;

    private JobDispatcherService dispatcher;

    /** Every job of the run under test, in declaration order. */
    private final Map<UUID, PipelineJob> jobsById = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        dispatcher = new JobDispatcherService(
                rabbitTemplate, pipelineRunRepository, pipelineStageRepository,
                pipelineJobRepository, jobAttemptRepository,
                runLocks, runStateSettler, outboxEventService);

        // Single-threaded tests: run the body inline so the dispatch pass itself is
        // what is under test, not the striped lock.
        lenient().when(runLocks.withRunLock(any(UUID.class), Mockito.<Supplier<Object>>any()))
                .thenAnswer(inv -> {
                    Supplier<?> body = inv.getArgument(1);
                    return body.get();
                });
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private PipelineRun run(String yaml) {
        PipelineVersion version = new PipelineVersion();
        version.setYamlContent(yaml);
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        run.setBranch("main");
        run.setCommitSha("abc123");
        run.setPipelineVersion(version);
        return run;
    }

    private PipelineStage stage(PipelineRun run, String name, int orderIndex,
                                PipelineStage.StageStatus status) {
        PipelineStage stage = new PipelineStage();
        stage.setName(name);
        stage.setStatus(status);
        stage.setOrderIndex(orderIndex);
        stage.setPipelineRun(run);
        setId(stage, UUID.randomUUID());
        return stage;
    }

    private PipelineJob job(PipelineStage stage, String name, PipelineJob.JobType type,
                            PipelineJob.JobStatus status) {
        PipelineJob job = new PipelineJob();
        job.setName(name);
        job.setJobType(type);
        job.setStatus(status);
        job.setPipelineStage(stage);
        setId(job, UUID.randomUUID());
        jobsById.put(job.getId(), job);
        return job;
    }

    /** Registers the run with the repositories and wires per-stage job lookups. */
    private void givenRun(PipelineRun run, PipelineStage... stages) {
        List<PipelineStage> stageList = new ArrayList<>(Arrays.asList(stages));
        Map<UUID, List<PipelineJob>> byStage = new LinkedHashMap<>();
        for (PipelineStage stage : stageList) {
            byStage.put(stage.getId(), stageJobs(stage));
        }
        when(pipelineRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(run.getId()))
                .thenReturn(stageList);
        for (Map.Entry<UUID, List<PipelineJob>> entry : byStage.entrySet()) {
            lenient().when(pipelineJobRepository.findByPipelineStageId(entry.getKey()))
                    .thenReturn(entry.getValue());
        }
    }

    /** Jobs attached to a stage, in creation order. */
    private List<PipelineJob> stageJobs(PipelineStage stage) {
        return jobsById.values().stream()
                .filter(j -> j.getPipelineStage() == stage)
                .toList();
    }

    /**
     * Emulates the conditional claim UPDATE and the post-claim re-read.
     *
     * @param alwaysLose job ids whose claim must lose, standing in for another
     *                   scheduler pass that already published them
     */
    private void stubClaims(List<UUID> alwaysLose) {
        lenient().when(pipelineJobRepository.claimForDispatch(any(), anyList(), any())).thenAnswer(inv -> {
            UUID jobId = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            List<PipelineJob.JobStatus> expected = inv.getArgument(1);
            PipelineJob.JobStatus target = inv.getArgument(2);
            PipelineJob job = jobsById.get(jobId);
            if (job == null || alwaysLose.contains(jobId) || !expected.contains(job.getStatus())) {
                return 0;
            }
            job.setStatus(target);
            return 1;
        });
        lenient().when(pipelineJobRepository.findById(any())).thenAnswer(
                inv -> Optional.ofNullable(jobsById.get((UUID) inv.getArgument(0))));
        lenient().when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(any())).thenReturn(List.of());
        lenient().when(jobAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubSkips() {
        lenient().when(pipelineJobRepository.markTerminal(any(), any(), any(), any())).thenAnswer(inv -> {
            UUID jobId = inv.getArgument(0);
            PipelineJob.JobStatus expected = inv.getArgument(1);
            PipelineJob.JobStatus target = inv.getArgument(2);
            PipelineJob job = jobsById.get(jobId);
            if (job == null || job.getStatus() != expected) {
                return 0;
            }
            job.setStatus(target);
            return 1;
        });
    }

    private void verifyPublished(int times) {
        verify(rabbitTemplate, times(times))
                .convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), any(Object.class));
    }

    // ------------------------------------------------------------------
    // Preconditions
    // ------------------------------------------------------------------

    @Test
    void dispatchReadyJobs_skipsWhenRunNotFound() {
        UUID runId = UUID.randomUUID();
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.empty());

        dispatcher.dispatchReadyJobs(runId);

        verify(pipelineStageRepository, never()).findByPipelineRunIdOrderByOrderIndexAsc(any());
        verifyPublished(0);
    }

    @Test
    void dispatchReadyJobs_skipsWhenRunNotRunning() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        run.setStatus(PipelineRun.RunStatus.FAILED);
        setId(run, UUID.randomUUID());
        when(pipelineRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        dispatcher.dispatchReadyJobs(run.getId());

        verify(pipelineStageRepository, never()).findByPipelineRunIdOrderByOrderIndexAsc(any());
        verifyPublished(0);
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void dispatchReadyJobs_dispatchesPendingJobs() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage test = stage(run, "test", 1, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        PipelineJob testJob = job(test, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, test);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, testJob.getStatus());
        assertEquals(PipelineJob.JobStatus.SUCCESS, compile.getStatus());
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_inActiveTransaction_defersPublishUntilAfterCommit() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        givenRun(run, build);
        stubClaims(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            dispatcher.dispatchReadyJobs(run.getId());

            // The claim and attempt rows are still uncommitted; publishing inside
            // the transaction would let a fast consumer see the job as PENDING and
            // drop the message as a lost claim. Nothing may be sent yet.
            assertEquals(PipelineJob.JobStatus.QUEUED, compile.getStatus());
            verifyPublished(0);

            List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(TransactionSynchronization::afterCommit);
            verifyPublished(1);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void dispatchReadyJobs_noTransaction_publishesImmediately() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        givenRun(run, build);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        verifyPublished(1);
    }

    @Test
    void dispatchJob_createsAttemptAndSendsMessage() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        stubClaims(List.of());

        assertTrue(dispatcher.dispatchJob(compile));

        assertEquals(PipelineJob.JobStatus.QUEUED, compile.getStatus());
        verify(jobAttemptRepository).save(any(JobAttempt.class));
        ArgumentCaptor<JobDispatchMessage> captor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), captor.capture());
        assertEquals("compile", captor.getValue().jobName());
        assertEquals(1, captor.getValue().attemptNumber());
    }

    @Test
    void dispatchJob_computesAttemptNumberPerJob() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        stubClaims(List.of());
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(compile.getId()))
                .thenReturn(List.of(new JobAttempt(compile, 2)));

        dispatcher.dispatchJob(compile);

        ArgumentCaptor<JobAttempt> attemptCaptor = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository).save(attemptCaptor.capture());
        assertEquals(3, attemptCaptor.getValue().getAttemptNumber());
        ArgumentCaptor<JobDispatchMessage> msgCaptor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), msgCaptor.capture());
        assertEquals(3, msgCaptor.getValue().attemptNumber());
    }

    @Test
    void dispatchJob_messageJobIdMatchesEntityId() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        stubClaims(List.of());

        dispatcher.dispatchJob(compile);

        ArgumentCaptor<JobDispatchMessage> captor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), captor.capture());
        assertEquals(compile.getId(), captor.getValue().jobId(),
                "Message jobId must match the persisted entity UUID");
    }

    @Test
    void dispatchJob_messageRunIdMatchesEntityChain() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        setId(run.getPipelineVersion(), UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        stubClaims(List.of());

        dispatcher.dispatchJob(compile);

        ArgumentCaptor<JobDispatchMessage> captor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), captor.capture());
        assertEquals(compile.getId(), captor.getValue().jobId());
        assertEquals(run.getId(), captor.getValue().runId());
        assertEquals(run.getPipelineVersion().getId(), captor.getValue().pipelineVersionId());
    }

    @Test
    void dispatchJob_lostClaim_publishesNothing() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        // A competing scheduler pass already claimed it.
        stubClaims(List.of(compile.getId()));

        assertFalse(dispatcher.dispatchJob(compile));

        verify(jobAttemptRepository, never()).save(any());
        verifyPublished(0);
    }

    @Test
    void dispatchForRetry_createsAttemptWithCorrectNumber() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.FAILED);
        PipelineJob deploy = job(build, "deploy", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.FAILED);
        stubClaims(List.of());

        dispatcher.dispatchForRetry(deploy, 3);

        assertEquals(PipelineJob.JobStatus.QUEUED, deploy.getStatus());
        ArgumentCaptor<JobAttempt> attemptCaptor = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository).save(attemptCaptor.capture());
        assertEquals(3, attemptCaptor.getValue().getAttemptNumber());
        verifyPublished(1);
    }

    @Test
    void recoverStaleDispatch_republishesLostMessage() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        UUID runId = UUID.randomUUID();
        setId(run, runId);
        setId(run.getPipelineVersion(), UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.QUEUED);
        stubClaims(List.of());

        assertTrue(dispatcher.recoverStaleDispatch(runId, compile.getId()));

        assertEquals(PipelineJob.JobStatus.QUEUED, compile.getStatus());
        ArgumentCaptor<JobAttempt> attemptCaptor = ArgumentCaptor.forClass(JobAttempt.class);
        verify(jobAttemptRepository).save(attemptCaptor.capture());
        assertEquals(1, attemptCaptor.getValue().getAttemptNumber());
        verifyPublished(1);
    }

    @Test
    void recoverStaleDispatch_lostClaim_publishesNothing() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        UUID runId = UUID.randomUUID();
        setId(run, runId);
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        // Not QUEUED anymore: a worker claimed it between the sweep's read and here.
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.RUNNING);
        stubClaims(List.of());

        assertFalse(dispatcher.recoverStaleDispatch(runId, compile.getId()));

        verify(jobAttemptRepository, never()).save(any());
        verifyPublished(0);
    }

    @Test
    void recoverStaleDispatch_nonRunningRun_doesNothing() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        UUID runId = UUID.randomUUID();
        setId(run, runId);
        run.setStatus(PipelineRun.RunStatus.FAILED);
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.QUEUED);
        stubClaims(List.of());

        assertFalse(dispatcher.recoverStaleDispatch(runId, compile.getId()));

        verifyPublished(0);
    }

    @Test
    void recoverStaleDispatch_unknownJob_doesNothing() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        UUID runId = UUID.randomUUID();
        setId(run, runId);
        stubClaims(List.of());

        assertFalse(dispatcher.recoverStaleDispatch(runId, UUID.randomUUID()));

        verifyPublished(0);
    }

    // ------------------------------------------------------------------
    // Stage-level dependencies
    // ------------------------------------------------------------------

    @Test
    void dispatchReadyJobs_failedStageBlocksDownstream() {
        PipelineRun run = run(DEPLOY_DEPENDS_ON_SECURITY_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage security = stage(run, "security", 0, PipelineStage.StageStatus.FAILED);
        PipelineStage deploy = stage(run, "deploy", 1, PipelineStage.StageStatus.PENDING);
        job(security, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.FAILED);
        PipelineJob publish = job(deploy, "publish", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.PENDING);
        givenRun(run, security, deploy);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.SKIPPED, publish.getStatus(),
                "deploy depends on a stage whose job FAILED, so it must be terminal SKIPPED, not PENDING");
        verifyPublished(0);
    }

    @Test
    void dispatchReadyJobs_previousStageNotSuccess_blocksDownstream() {
        PipelineRun run = run(DEPLOY_DEPENDS_ON_SECURITY_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage security = stage(run, "security", 0, PipelineStage.StageStatus.RUNNING);
        PipelineStage deploy = stage(run, "deploy", 1, PipelineStage.StageStatus.PENDING);
        job(security, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.RUNNING);
        PipelineJob publish = job(deploy, "publish", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.PENDING);
        givenRun(run, security, deploy);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.PENDING, publish.getStatus(),
                "A RUNNING dependency is not yet decidable, so the job must wait, not be skipped");
        verifyPublished(0);
    }

    @Test
    void dispatchReadyJobs_successfulStageUnlocksNext() {
        PipelineRun run = run(DEPLOY_DEPENDS_ON_SECURITY_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage security = stage(run, "security", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage deploy = stage(run, "deploy", 1, PipelineStage.StageStatus.PENDING);
        job(security, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.SUCCESS);
        PipelineJob publish = job(deploy, "publish", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.PENDING);
        givenRun(run, security, deploy);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, publish.getStatus());
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_dependsOnDispatchesWhenDependencySucceeds() {
        PipelineRun run = run(DEPLOY_DEPENDS_ON_SECURITY_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage security = stage(run, "security", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage deploy = stage(run, "deploy", 1, PipelineStage.StageStatus.PENDING);
        job(security, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.SUCCESS);
        PipelineJob publish = job(deploy, "publish", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.PENDING);
        givenRun(run, security, deploy);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, publish.getStatus(),
                "deploy should be dispatched because security dependency is SUCCESS");
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_dependsOnBlocksIfEitherDependencyNotSuccess() {
        PipelineRun run = run(DEPLOY_DEPENDS_ON_BUILD_AND_SECURITY_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage security = stage(run, "security", 1, PipelineStage.StageStatus.RUNNING);
        PipelineStage deploy = stage(run, "deploy", 2, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        job(security, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.RUNNING);
        PipelineJob publish = job(deploy, "publish", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, security, deploy);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.PENDING, publish.getStatus(),
                "deploy should stay PENDING because security (second dependency) is not SUCCESS");
        verifyPublished(0);
    }

    @Test
    void dispatchReadyJobs_dependsOnIgnoresUnrelatedEarlierStageFailure() {
        PipelineRun run = run(DEPLOY_DEPENDS_ON_SECURITY_THREE_STAGE_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.FAILED);
        PipelineStage security = stage(run, "security", 1, PipelineStage.StageStatus.SUCCESS);
        PipelineStage deploy = stage(run, "deploy", 2, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.FAILED);
        job(security, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.SUCCESS);
        PipelineJob publish = job(deploy, "publish", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, security, deploy);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, publish.getStatus(),
                "deploy depends on security (not build), so build failure should not block deploy");
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_noDependsOn_usesPositionalOrdering() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage test = stage(run, "test", 1, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        PipelineJob testJob = job(test, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, test);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, testJob.getStatus(),
                "Without dependsOn, positional ordering should dispatch test after build succeeds");
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_noDependsOn_blocksSecondStageWhileFirstRunning() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.RUNNING);
        PipelineStage test = stage(run, "test", 1, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.RUNNING);
        PipelineJob testJob = job(test, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, test);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.PENDING, testJob.getStatus(),
                "Positional fallback must keep waiting while the first stage is still running");
        verifyPublished(0);
    }

    // ------------------------------------------------------------------
    // Job-level dependsOn: this is where real parallelism comes from
    // ------------------------------------------------------------------

    @Test
    void dispatchReadyJobs_jobDependsOn_blocksWhileDependencyPending() {
        PipelineRun run = run(JOB_DEP_WITHIN_STAGE_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        PipelineJob testJob = job(build, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, compile.getStatus(), "compile has no deps, should be dispatched");
        assertEquals(PipelineJob.JobStatus.PENDING, testJob.getStatus(),
                "unit-test depends on compile which is not SUCCESS yet, should stay PENDING");
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_jobDependsOn_blocksWhileDependencyRunning() {
        PipelineRun run = run(JOB_DEP_WITHIN_STAGE_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.RUNNING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.RUNNING);
        PipelineJob testJob = job(build, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.RUNNING, compile.getStatus(),
                "compile is already running, not PENDING, so not dispatched");
        assertEquals(PipelineJob.JobStatus.PENDING, testJob.getStatus(),
                "unit-test depends on compile which is RUNNING, should stay PENDING");
        verifyPublished(0);
    }

    @Test
    void dispatchReadyJobs_jobDependsOn_dispatchesWhenDependencySuccess() {
        PipelineRun run = run(JOB_DEP_WITHIN_STAGE_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.RUNNING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        PipelineJob testJob = job(build, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, testJob.getStatus(),
                "unit-test depends on compile which is SUCCESS, should be dispatched");
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_multipleJobDeps_allMustBeSuccess() {
        PipelineRun run = run(MULTI_JOB_DEP_WITHIN_STAGE_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        PipelineJob lint = job(build, "lint", PipelineJob.JobType.CUSTOM, PipelineJob.JobStatus.PENDING);
        PipelineJob testJob = job(build, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, compile.getStatus());
        assertEquals(PipelineJob.JobStatus.QUEUED, lint.getStatus());
        assertEquals(PipelineJob.JobStatus.PENDING, testJob.getStatus(),
                "unit-test depends on compile and lint, neither is SUCCESS yet, should stay PENDING");
        // Two independent jobs published in the same pass: the scheduler does not
        // wait for the first one to finish.
        verifyPublished(2);
    }

    @Test
    void dispatchReadyJobs_independentBranches_publishedInOnePass() {
        PipelineRun run = run(FAN_OUT_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage verify = stage(run, "verify", 1, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        PipelineJob test = job(verify, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        PipelineJob scan = job(verify, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, verify);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, test.getStatus());
        assertEquals(PipelineJob.JobStatus.QUEUED, scan.getStatus(),
                "scan has no dependency on test, so both must be claimed in the same pass");
        verifyPublished(2);
    }

    @Test
    void dispatchReadyJobs_independentBranchFailure_skipsOnlyItsDependent() {
        PipelineRun run = run(FAN_OUT_THEN_JOIN_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage verify = stage(run, "verify", 1, PipelineStage.StageStatus.RUNNING);
        PipelineStage packageStage = stage(run, "package", 2, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        job(verify, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.SUCCESS);
        job(verify, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.FAILED);
        PipelineJob pack = job(packageStage, "assemble", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, verify, packageStage);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.SKIPPED, pack.getStatus(),
                "package waits on both verify jobs; scan FAILED, so package is unreachable");
        verifyPublished(0);
    }

    @Test
    void dispatchReadyJobs_failurePropagatesTransitivelyInOnePass() {
        PipelineRun run = run(TRANSITIVE_CHAIN_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage chain = stage(run, "chain", 0, PipelineStage.StageStatus.RUNNING);
        job(chain, "a", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.FAILED);
        PipelineJob b = job(chain, "b", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        PipelineJob c = job(chain, "c", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        PipelineJob d = job(chain, "d", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        givenRun(run, chain);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.SKIPPED, b.getStatus());
        assertEquals(PipelineJob.JobStatus.SKIPPED, c.getStatus(),
                "c depends on b, which is itself unreachable; blocking must propagate without a second pass");
        assertEquals(PipelineJob.JobStatus.SKIPPED, d.getStatus());
        verifyPublished(0);
    }

    @Test
    void dispatchReadyJobs_skips_settlesRunSoItCanReachTerminalState() {
        PipelineRun run = run(DEPLOY_DEPENDS_ON_SECURITY_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage security = stage(run, "security", 0, PipelineStage.StageStatus.FAILED);
        PipelineStage deploy = stage(run, "deploy", 1, PipelineStage.StageStatus.PENDING);
        job(security, "scan", PipelineJob.JobType.SCAN, PipelineJob.JobStatus.FAILED);
        job(deploy, "publish", PipelineJob.JobType.DEPLOY, PipelineJob.JobStatus.PENDING);
        givenRun(run, security, deploy);
        stubClaims(List.of());
        stubSkips();

        dispatcher.dispatchReadyJobs(run.getId());

        // Without this the run would sit RUNNING forever: the last thing that
        // happened was a skip, not a completion, so no completion callback fires.
        verify(runStateSettler).settleRun(run.getId());
    }

    @Test
    void dispatchReadyJobs_doesNotSettleWhenNothingWasSkipped() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage test = stage(run, "test", 1, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        job(test, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, test);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        verify(runStateSettler, never()).settleRun(any());
    }

    @Test
    void dispatchReadyJobs_publishesDeclaredStepsWithTheMessage() {
        PipelineRun run = run(STEPS_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.PENDING);
        PipelineJob compile = job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.PENDING);
        givenRun(run, build);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        ArgumentCaptor<JobDispatchMessage> captor = ArgumentCaptor.forClass(JobDispatchMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(JOB_DISPATCH_EXCHANGE), eq(JOB_DISPATCH_ROUTING_KEY), captor.capture());
        List<StepDefinition> steps = captor.getValue().steps();
        assertEquals(2, steps.size(), "Both declared steps must travel with the message");
        assertEquals("javac", steps.get(0).name());
        assertEquals("jar cf app.jar -C out .", steps.get(1).run());
        assertTrue(captor.getValue().hasDeclaredSteps());
    }

    @Test
    void dispatchReadyJobs_unresolvableGraph_fallsBackToPositionalOrdering() {
        // A dependency that names nothing cannot be resolved, so the registry
        // returns no graph and the run must still make progress.
        PipelineRun run = run(UNRESOLVABLE_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage test = stage(run, "test", 1, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        PipelineJob testJob = job(test, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, test);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());

        assertEquals(PipelineJob.JobStatus.QUEUED, testJob.getStatus(),
                "An unresolvable declaration must not stall the run");
        verifyPublished(1);
    }

    @Test
    void dispatchReadyJobs_isIdempotentAcrossPasses() {
        PipelineRun run = run(NO_DEPENDS_ON_YAML);
        setId(run, UUID.randomUUID());
        PipelineStage build = stage(run, "build", 0, PipelineStage.StageStatus.SUCCESS);
        PipelineStage test = stage(run, "test", 1, PipelineStage.StageStatus.PENDING);
        job(build, "compile", PipelineJob.JobType.BUILD, PipelineJob.JobStatus.SUCCESS);
        job(test, "unit-test", PipelineJob.JobType.TEST, PipelineJob.JobStatus.PENDING);
        givenRun(run, build, test);
        stubClaims(List.of());

        dispatcher.dispatchReadyJobs(run.getId());
        // Second pass sees the same rows; the job is QUEUED now, so the claim fails.
        dispatcher.dispatchReadyJobs(run.getId());

        verifyPublished(1);
    }

    // ------------------------------------------------------------------
    // YAML fixtures
    // ------------------------------------------------------------------

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

    private static final String TRANSITIVE_CHAIN_YAML = """
            pipeline:
              name: chain-test
              stages:
                - name: chain
                  jobs:
                    - name: a
                      type: BUILD
                    - name: b
                      type: BUILD
                      dependsOn:
                        - a
                    - name: c
                      type: BUILD
                      dependsOn:
                        - b
                    - name: d
                      type: BUILD
                      dependsOn:
                        - c
            """;

    private static final String FAN_OUT_YAML = """
            pipeline:
              name: fan-out
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                - name: verify
                  dependsOn:
                    - build
                  jobs:
                    - name: unit-test
                      type: TEST
                    - name: scan
                      type: SCAN
            """;

    private static final String FAN_OUT_THEN_JOIN_YAML = """
            pipeline:
              name: fan-out-join
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                - name: verify
                  dependsOn:
                    - build
                  jobs:
                    - name: unit-test
                      type: TEST
                    - name: scan
                      type: SCAN
                - name: package
                  dependsOn:
                    - verify
                  jobs:
                    - name: assemble
                      type: BUILD
            """;

    private static final String STEPS_YAML = """
            pipeline:
              name: steps-test
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                      steps:
                        - name: javac
                          run: javac -d out src/Main.java
                        - name: jar
                          run: jar cf app.jar -C out .
            """;

    private static final String UNRESOLVABLE_YAML = """
            pipeline:
              name: unresolvable
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                - name: test
                  dependsOn:
                    - stage-that-does-not-exist
                  jobs:
                    - name: unit-test
                      type: TEST
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

    private static void setId(Object entity, UUID id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
