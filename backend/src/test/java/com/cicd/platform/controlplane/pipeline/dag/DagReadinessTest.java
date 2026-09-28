package com.cicd.platform.controlplane.pipeline.dag;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.JobDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StageDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagReadinessTest {

    private static JobDefinition def(String name, String... dependsOn) {
        return new JobDefinition(name, PipelineJob.JobType.CUSTOM, List.of(dependsOn));
    }

    private static StageDefinition stageDef(String name, JobDefinition... jobs) {
        return new StageDefinition(name, 0, List.of(), List.of(jobs));
    }

    private static StageDefinition stageDefDep(String name, List<String> dependsOn, JobDefinition... jobs) {
        return new StageDefinition(name, 0, dependsOn, List.of(jobs));
    }

    private final List<PipelineJob> jobs = new ArrayList<>();

    private PipelineJob addJob(PipelineStage stage, String name, PipelineJob.JobStatus status) throws Exception {
        PipelineJob job = new PipelineJob(null, name, PipelineJob.JobType.CUSTOM);
        Field field = PipelineJob.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(job, UUID.randomUUID());
        job.setPipelineStage(stage);
        job.setStatus(status);
        jobs.add(job);
        return job;
    }

    private PipelineStage stage(String name) {
        PipelineStage stage = new PipelineStage(null, name, 0);
        try {
            Field field = PipelineStage.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(stage, UUID.randomUUID());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return stage;
    }

    private DagReadiness.Plan plan(List<StageDefinition> definitions) {
        return DagReadiness.evaluate(PipelineDag.from(definitions), jobs);
    }

    private static void assertRunnableKeys(DagReadiness.Plan p, Collection<PipelineJob> jobs, String... keys) {
        Set<String> actual = DagReadiness.keysOf(p.runnableJobIds(), jobs);
        assertEquals(Set.of(keys), actual);
    }

    @Test
    void noPendingJobs_returnsEmptyPlan() throws Exception {
        PipelineStage s = stage("build");
        addJob(s, "compile", PipelineJob.JobStatus.RUNNING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("compile"))));

        assertFalse(p.hasWork());
        assertEquals(List.of(), p.runnableJobIds());
        assertEquals(List.of(), p.blockedJobs());
        assertEquals(List.of(), p.waitingJobIds());
    }

    @Test
    void independentJob_withEmptyDependencies_isImmediatelyRunnable() throws Exception {
        PipelineStage s = stage("build");
        PipelineJob compile = addJob(s, "compile", PipelineJob.JobStatus.PENDING);
        PipelineJob lint = addJob(s, "lint", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("compile"), def("lint"))));

        assertRunnableKeys(p, jobs, "build/compile", "build/lint");
        assertTrue(p.runnableJobIds().contains(compile.getId()));
        assertTrue(p.runnableJobIds().contains(lint.getId()));
        assertEquals("build/compile", DagReadiness.keyOf(compile));
        assertTrue(p.blockedJobs().isEmpty());
    }

    @Test
    void jobWithSuccessfulDependency_isRunnable() throws Exception {
        PipelineStage s = stage("build");
        addJob(s, "compile", PipelineJob.JobStatus.SUCCESS);
        PipelineJob packageJob = addJob(s, "package", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("compile"), def("package", "compile"))));

        assertTrue(p.runnableJobIds().contains(packageJob.getId()));
    }

    @Test
    void jobWithRunningDependency_isWaitingNotBlocked() throws Exception {
        PipelineStage s = stage("build");
        addJob(s, "compile", PipelineJob.JobStatus.RUNNING);
        PipelineJob packageJob = addJob(s, "package", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("compile"), def("package", "compile"))));

        assertFalse(p.runnableJobIds().contains(packageJob.getId()));
        assertTrue(p.waitingJobIds().contains(packageJob.getId()));
        assertTrue(p.blockedJobs().isEmpty());
    }

    @Test
    void jobWithQueuedDependency_isWaitingNotBlocked() throws Exception {
        PipelineStage s = stage("build");
        addJob(s, "compile", PipelineJob.JobStatus.QUEUED);
        PipelineJob packageJob = addJob(s, "package", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("compile"), def("package", "compile"))));

        assertTrue(p.waitingJobIds().contains(packageJob.getId()));
        assertTrue(p.blockedJobs().isEmpty());
    }

    @Test
    void jobWithFailedDependency_isBlocked() throws Exception {
        PipelineStage s = stage("build");
        addJob(s, "compile", PipelineJob.JobStatus.FAILED);
        PipelineJob packageJob = addJob(s, "package", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("compile"), def("package", "compile"))));

        assertTrue(p.hasWork());
        assertTrue(p.runnableJobIds().isEmpty());
        assertEquals(1, p.blockedJobs().size());
        assertEquals("build/compile", p.blockedJobs().get(0).blockedBy());
        assertEquals(packageJob.getId(), p.blockedJobs().get(0).jobId());
    }

    @Test
    void cancelledAndSkippedDependencies_alsoBlock() throws Exception {
        PipelineStage s = stage("build");
        addJob(s, "a", PipelineJob.JobStatus.CANCELLED);
        addJob(s, "b", PipelineJob.JobStatus.SKIPPED);
        addJob(s, "c", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("a"), def("b"), def("c", "a", "b"))));

        assertEquals(1, p.blockedJobs().size());
        assertTrue(p.runnableJobIds().isEmpty());
    }

    @Test
    void blockingPropagatesThroughChain_inOnePass() throws Exception {
        PipelineStage build = stage("build");
        PipelineJob a = addJob(build, "a", PipelineJob.JobStatus.FAILED);
        PipelineStage test = stage("test");
        PipelineJob b = addJob(test, "b", PipelineJob.JobStatus.PENDING);
        PipelineStage deploy = stage("deploy");
        PipelineJob c = addJob(deploy, "c", PipelineJob.JobStatus.PENDING);

        PipelineDag dag = PipelineDag.from(List.of(
                stageDef("build", def("a")),
                stageDefDep("test", List.of("build"), def("b")),
                stageDefDep("deploy", List.of("test"), def("c"))));

        DagReadiness.Plan p = DagReadiness.evaluate(dag, jobs);

        assertTrue(p.blockedJobs().stream().anyMatch(bj -> bj.jobId().equals(b.getId())));
        assertTrue(p.blockedJobs().stream().anyMatch(bj -> bj.jobId().equals(c.getId())));
        assertEquals(2, p.blockedJobs().size());
        assertEquals(List.of(), p.runnableJobIds());
        assertEquals(List.of(), p.waitingJobIds());
    }

    @Test
    void successfulUpstreamInOneBranch_doesNotBlockIndependentBranch() throws Exception {
        PipelineStage deploy = stage("deploy");
        addJob(deploy, "a", PipelineJob.JobStatus.FAILED);
        addJob(deploy, "b", PipelineJob.JobStatus.SUCCESS);
        PipelineJob c = addJob(deploy, "c", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("deploy", def("a"), def("b"), def("c", "b"))));

        assertRunnableKeys(p, jobs, "deploy/c");
        assertTrue(p.blockedJobs().isEmpty());
    }

    @Test
    void jobWithoutDagEntry_isRunnableAndReportedUnmapped() throws Exception {
        PipelineStage s = stage("build");
        PipelineJob legacy = addJob(s, "legacy", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("other"))));

        assertTrue(p.runnableJobIds().contains(legacy.getId()));
        assertTrue(p.unmappedJobIds().contains(legacy.getId()));
        assertFalse(p.waitingJobIds().contains(legacy.getId()));
    }

    @Test
    void mixedStates_partitionIntoRunnableWaitingAndBlocked() throws Exception {
        PipelineStage s = stage("build");
        addJob(s, "a", PipelineJob.JobStatus.SUCCESS);
        addJob(s, "b", PipelineJob.JobStatus.FAILED);
        addJob(s, "x1", PipelineJob.JobStatus.PENDING);
        addJob(s, "x4", PipelineJob.JobStatus.PENDING);
        addJob(s, "x2", PipelineJob.JobStatus.PENDING);
        addJob(s, "x3", PipelineJob.JobStatus.PENDING);

        PipelineDag dag = PipelineDag.from(List.of(stageDef("build",
                def("a"),
                def("b"),
                def("x1"),
                def("x2", "a"),
                def("x3", "b"),
                def("x4", "x1"))));

        DagReadiness.Plan p = DagReadiness.evaluate(dag, jobs);

        assertRunnableKeys(p, jobs, "build/x1", "build/x2");
        assertEquals(1, p.blockedJobs().size());
        assertEquals(1, p.waitingJobIds().size());
    }

    @Test
    void keysOf_mapsRunnableIdsToKeys() throws Exception {
        PipelineStage s = stage("build");
        PipelineJob a = addJob(s, "a", PipelineJob.JobStatus.PENDING);

        DagReadiness.Plan p = plan(List.of(stageDef("build", def("a"))));

        assertRunnableKeys(p, jobs, "build/a");
        assertEquals(a.getId(), p.runnableJobIds().get(0));
    }
}