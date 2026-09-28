package com.cicd.platform.controlplane.pipeline.dag;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineDagRegistryTest {

    private final PipelineDagRegistry registry = new PipelineDagRegistry();

    private PipelineVersion version;

    @BeforeEach
    void setUp() throws Exception {
        version = new PipelineVersion(null, 1,
                "pipeline:\n"
                        + "  name: p\n"
                        + "  stages:\n"
                        + "    - name: build\n"
                        + "      jobs:\n"
                        + "        - name: compile\n"
                        + "          type: build\n"
                        + "        - name: package\n"
                        + "          dependsOn: [compile]\n"
                        + "          steps:\n"
                        + "            - name: package-jar\n"
                        + "              run: mvn -q package\n"
                        + "    - name: test\n"
                        + "      dependsOn: [build]\n"
                        + "      jobs:\n"
                        + "        - name: run-tests\n"
                        + "          type: test\n",
                "abc", "alice");
        setId(version, UUID.randomUUID());
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    @Test
    void fromVersion_buildsResolvedDag() {
        PipelineDag dag = registry.fromVersion(version);

        assertNotNull(dag);
        assertTrue(dag.hasNode("build/compile"));
        assertTrue(dag.dependenciesOf("test/run-tests").contains("build/compile"));
        assertTrue(dag.dependenciesOf("build/package").contains("build/compile"));
    }

    @Test
    void fromVersion_isCached_sameInstance() {
        PipelineDag first = registry.fromVersion(version);
        PipelineDag second = registry.fromVersion(version);

        assertSame(first, second);
        // The second call resolves from the graph cache without re-parsing YAML:
        // one hit. The first call missed once on the graph cache and once on the
        // (internally resolved) stage-definition cache.
        assertEquals(1, registry.cacheHits());
        assertEquals(2, registry.cacheMisses());
        assertTrue(registry.cacheSize() >= 1);
    }

    @Test
    void fromVersion_changeInYamlLanguage_evictsCacheKey() throws Exception {
        PipelineVersion other = new PipelineVersion(null, 1,
                "pipeline:\n"
                        + "  name: p\n"
                        + "  stages:\n"
                        + "    - name: build\n"
                        + "      jobs:\n"
                        + "        - name: compile\n",
                "abc", "alice");
        setId(other, version.getId());

        PipelineDag first = registry.fromVersion(version);
        PipelineDag second = registry.fromVersion(other);

        assertTrue(first != second);
        // Four misses: stage definitions and graph for each of the two versions.
        assertEquals(4, registry.cacheMisses());
    }

    @Test
    void fromVersion_nullVersion_returnsNull() {
        assertNull(registry.fromVersion(null));
    }

    @Test
    void fromVersion_blankYaml_returnsNull() {
        version.setYamlContent("   ");
        assertNull(registry.fromVersion(version));
    }

    @Test
    void fromVersion_cyclicDeclaration_returnsNullInsteadOfThrowing() {
        version.setYamlContent("pipeline:\n  name: p\n  stages:\n"
                + "    - name: a\n      dependsOn: [b]\n      jobs:\n        - name: j1\n"
                + "    - name: b\n      dependsOn: [a]\n      jobs:\n        - name: j2\n");
        assertNull(registry.fromVersion(version));
    }

    @Test
    void stageDefinitionsOf_returnsAllStagesAndSteps() {
        List<StepDefinition> steps = registry.stepsFor(version, "build", "package");

        assertEquals(1, steps.size());
        assertEquals("package-jar", steps.get(0).name());
        assertEquals("mvn -q package", steps.get(0).run());

        List<PipelineConfigMapper.StageDefinition> definitions = registry.stageDefinitionsOf(version);
        assertEquals(2, definitions.size());
    }

    @Test
    void stepsFor_unknownJob_returnsEmptyList() {
        assertEquals(List.of(), registry.stepsFor(version, "build", "missing"));
        assertEquals(List.of(), registry.stepsFor(version, "missing-stage", "compile"));
    }

    @Test
    void fromPersisted_buildsDagFromDependencyColumns() throws Exception {
        PipelineStage build = stageWithId("build", 0);
        build.setDependencies(List.of());
        PipelineJob compile = jobWithId(build, "compile", PipelineJob.JobType.BUILD);
        compile.setDependencies(List.of());
        PipelineJob packageJob = jobWithId(build, "package", PipelineJob.JobType.PACKAGE);
        packageJob.setDependencies(List.of("compile"));

        PipelineStage test = stageWithId("test", 1);
        test.setDependencies(List.of("build"));
        PipelineJob runTests = jobWithId(test, "run-tests", PipelineJob.JobType.TEST);
        runTests.setDependencies(List.of());

        Map<UUID, List<PipelineJob>> jobsByStage = new LinkedHashMap<>();
        jobsByStage.put(build.getId(), List.of(compile, packageJob));
        jobsByStage.put(test.getId(), List.of(runTests));

        PipelineDag dag = registry.fromPersisted(List.of(build, test), jobsByStage);

        assertNotNull(dag);
        assertTrue(dag.dependenciesOf("build/package").contains("build/compile"));
        assertTrue(dag.dependenciesOf("test/run-tests").contains("build/compile"));
    }

    @Test
    void fromPersisted_nothingDeclared_returnsNull() throws Exception {
        PipelineStage build = stageWithId("build", 0);
        PipelineJob compile = jobWithId(build, "compile", PipelineJob.JobType.BUILD);

        PipelineDag dag = registry.fromPersisted(List.of(build),
                Map.of(build.getId(), List.of(compile)));

        assertNull(dag);
    }

    @Test
    void resolveForRun_prefersPersistedEdges() throws Exception {
        PipelineStage build = stageWithId("build", 0);
        PipelineJob compile = jobWithId(build, "compile", PipelineJob.JobType.BUILD);
        PipelineStage test = stageWithId("test", 1);
        test.setDependencies(List.of("build"));
        PipelineJob runTests = jobWithId(test, "run-tests", PipelineJob.JobType.TEST);

        PipelineDag dag = registry.resolveForRun(version, List.of(build, test),
                Map.of(build.getId(), List.of(compile), test.getId(), List.of(runTests)));

        assertNotNull(dag);
        assertEquals(2, dag.jobKeys().size());
    }

    @Test
    void resolveForRun_fallsBackToVersionYaml_whenNothingPersisted() {
        PipelineDag dag = registry.resolveForRun(version, List.of(), Map.of());

        assertNotNull(dag);
        assertTrue(dag.hasNode("build/compile"));
        assertEquals(3, dag.jobKeys().size());
    }

    private static PipelineStage stageWithId(String name, int orderIndex) throws Exception {
        PipelineStage stage = new PipelineStage(null, name, orderIndex);
        setId(stage, UUID.randomUUID());
        return stage;
    }

    private static PipelineJob jobWithId(PipelineStage stage, String name, PipelineJob.JobType type)
            throws Exception {
        PipelineJob job = new PipelineJob(stage, name, type);
        setId(job, UUID.randomUUID());
        return job;
    }
}