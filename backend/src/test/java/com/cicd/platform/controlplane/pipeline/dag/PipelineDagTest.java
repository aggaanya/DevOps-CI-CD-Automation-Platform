package com.cicd.platform.controlplane.pipeline.dag;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.JobDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StageDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineDagTest {

    private static JobDefinition job(String name) {
        return new JobDefinition(name, PipelineJob.JobType.CUSTOM, List.of());
    }

    private static JobDefinition job(String name, String... dependsOn) {
        return new JobDefinition(name, PipelineJob.JobType.CUSTOM, List.of(dependsOn));
    }

    private static StageDefinition stage(String name, JobDefinition... jobs) {
        return new StageDefinition(name, 0, List.of(), List.of(jobs));
    }

    private static StageDefinition stage(String name, List<String> dependsOn, JobDefinition... jobs) {
        return new StageDefinition(name, 0, dependsOn == null ? List.of() : dependsOn, List.of(jobs));
    }

    @Test
    void noDeclaredDependencies_meansStrictlySequentialStages() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("build", job("compile")),
                stage("test", job("run-tests"))));

        assertTrue(dag.hasNode("build/compile"));
        assertTrue(dag.dependenciesOf("test/run-tests").contains("build/compile"));
        assertEquals(List.of(), dag.dependenciesOf("build/compile"));
    }

    @Test
    void independentJobsInSameStage_areSimultaneouslyEligible() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("build", job("compile-linux"), job("compile-windows"))));

        assertEquals(List.of(), dag.dependenciesOf("build/compile-linux"));
        assertEquals(List.of(), dag.dependenciesOf("build/compile-windows"));
        assertEquals(2, dag.jobKeys().size());
    }

    @Test
    void sequentialJobsInSameStage_areChained() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("build", job("compile"), job("package", "compile"))));

        assertTrue(dag.dependenciesOf("build/package").contains("build/compile"));
        assertEquals(List.of(), dag.dependenciesOf("build/compile"));
    }

    @Test
    void explicitStageDependency_overridesPositionalFallback() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("build", job("compile")),
                stage("test", job("run-tests")),
                stage("deploy", List.of("build"), job("push"))));

        assertTrue(dag.dependenciesOf("deploy/push").contains("build/compile"));
        assertTrue(!dag.dependenciesOf("deploy/push").contains("test/run-tests"));
    }

    @Test
    void dependencyNames_areNormalizedToLowerCase() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("Build", job("Compile"), job("Package", "COMPILE")),
                stageWithDep("Test", "BUILD", job("Run-Tests"))));

        assertTrue(dag.hasNode("build/compile"));
        assertTrue(dag.hasNode("build/package"));
        assertTrue(dag.hasNode("test/run-tests"));
        assertTrue(dag.dependenciesOf("build/package").contains("build/compile"));
        assertTrue(dag.dependenciesOf("test/run-tests").contains("build/compile"));
        assertTrue(dag.dependenciesOf("test/run-tests").contains("build/package"));
    }

    private static StageDefinition stageWithDep(String name, String dependsOn, JobDefinition... jobs) {
        return new StageDefinition(name, 0, List.of(dependsOn), List.of(jobs));
    }

    @Test
    void selfDependency_throwsCyclic() {
        PipelineDag.CyclicDependencyException ex = assertThrows(
                PipelineDag.CyclicDependencyException.class,
                () -> PipelineDag.from(List.of(stage("s", job("a", "a")))));
        assertTrue(ex.getMessage().contains("depends on itself"));
    }

    @Test
    void duplicateJobName_throws() {
        PipelineDag.CyclicDependencyException ex = assertThrows(
                PipelineDag.CyclicDependencyException.class,
                () -> PipelineDag.from(List.of(stage("s", job("a"), job("A")))));
        assertTrue(ex.getMessage().contains("Duplicate job"));
    }

    @Test
    void unknownStageDependency_throwsUnresolved() {
        PipelineDag.UnresolvedDependencyException ex = assertThrows(
                PipelineDag.UnresolvedDependencyException.class,
                () -> PipelineDag.from(List.of(stage("test", List.of("missing"), job("a")))));
        assertTrue(ex.getMessage().contains("unknown stage"));
    }

    @Test
    void unknownJobDependency_throwsUnresolved() {
        PipelineDag.UnresolvedDependencyException ex = assertThrows(
                PipelineDag.UnresolvedDependencyException.class,
                () -> PipelineDag.from(List.of(stage("s", job("a", "missing")))));
        assertTrue(ex.getMessage().contains("unknown job"));
    }

    @Test
    void jobLevelCycle_throwsCyclic() {
        assertThrows(PipelineDag.CyclicDependencyException.class,
                () -> PipelineDag.from(List.of(stage("s", job("a", "b"), job("b", "a")))));
    }

    @Test
    void topologicalOrder_isDeterministicAndRespectsEdges() {
        List<StageDefinition> definitions = List.of(
                stage("build", job("compile"), job("lint")),
                stage("test", List.of("build"), job("unit-tests"), job("e2e")));

        PipelineDag first = PipelineDag.from(definitions);
        PipelineDag second = PipelineDag.from(definitions);

        assertEquals(first.topologicalOrder(), second.topologicalOrder());

        List<String> order = first.topologicalOrder();
        for (String edgeSource : List.of("build/compile", "build/lint")) {
            assertTrue(order.indexOf(edgeSource) < order.indexOf("test/unit-tests"));
            assertTrue(order.indexOf(edgeSource) < order.indexOf("test/e2e"));
        }
        assertEquals(4, order.size());
    }

    @Test
    void dependentsOf_returnsNodesUnblockedBySuccess() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("build", job("compile")),
                stage("test", job("run-tests"))));

        assertTrue(dag.dependentsOf("build/compile").contains("test/run-tests"));
    }

    @Test
    void jobsByStage_groupsInDeclaredOrder() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("build", job("compile"), job("lint")),
                stage("test", job("run-tests"))));

        Map<String, List<String>> grouped = dag.jobsByStage();
        assertEquals(List.of("build/compile", "build/lint"), grouped.get("build"));
        assertEquals(List.of("test/run-tests"), grouped.get("test"));
    }

    @Test
    void stageAndJobHelpers_resolveKeys() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("Build", job("Compile"))));

        assertEquals("build", dag.stageOf("build/compile"));
        assertEquals("compile", dag.jobNameOf("build/compile"));
        assertEquals("build/build", PipelineDag.key("BUILD", "Build"));
    }

    @Test
    void returnedAdjacencyLists_areImmutable() {
        PipelineDag dag = PipelineDag.from(List.of(
                stage("build", job("compile"), job("lint", "compile"))));

        assertThrows(UnsupportedOperationException.class,
                () -> dag.dependenciesOf("build/lint").add("build/compile"));
        assertThrows(UnsupportedOperationException.class,
                () -> dag.dependentsOf("build/compile").add("build/lint"));
    }
}