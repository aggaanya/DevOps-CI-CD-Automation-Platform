package com.cicd.platform.controlplane.pipeline;

import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.pipeline.config.JobConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigMapperTest {

    private PipelineConfigMapper mapper;
    private Project project;

    @BeforeEach
    void setUp() {
        mapper = new PipelineConfigMapper();
        Organization org = new Organization("Test Org", "test-org", "Test org description");
        project = new Project(org, "Test Project", "test-project", "Test project description");
    }

    @Test
    void toPipelineShouldMapFields() {
        PipelineConfig config = new PipelineConfig("my-pipeline", "A description", new ArrayList<>());

        Pipeline pipeline = mapper.toPipeline(config, project);

        assertNotNull(pipeline);
        assertEquals("my-pipeline", pipeline.getName());
        assertEquals("A description", pipeline.getDescription());
        assertEquals(project, pipeline.getProject());
    }

    @Test
    void toStageDefinitionsShouldMapOrderIndex() {
        StageConfig s1 = createStage("build", List.of(createJob("compile", "BUILD")));
        StageConfig s2 = createStage("test", List.of(createJob("unit-test", "TEST")));
        StageConfig s3 = createStage("deploy", List.of(createJob("publish", "DEPLOY")));
        PipelineConfig config = new PipelineConfig("pipeline", "desc", List.of(s1, s2, s3));

        List<PipelineConfigMapper.StageDefinition> definitions = mapper.toStageDefinitions(config);

        assertEquals(3, definitions.size());
        assertEquals(0, definitions.get(0).orderIndex());
        assertEquals(1, definitions.get(1).orderIndex());
        assertEquals(2, definitions.get(2).orderIndex());
        assertEquals("build", definitions.get(0).name());
        assertEquals("test", definitions.get(1).name());
        assertEquals("deploy", definitions.get(2).name());
    }

    @Test
    void toStageDefinitionsShouldMapJobs() {
        JobConfig job1 = createJob("compile", "BUILD");
        JobConfig job2 = createJob("package", "PACKAGE");
        StageConfig stage = createStage("build", List.of(job1, job2));
        PipelineConfig config = new PipelineConfig("pipeline", "desc", List.of(stage));

        List<PipelineConfigMapper.StageDefinition> definitions = mapper.toStageDefinitions(config);

        assertEquals(1, definitions.size());
        List<PipelineConfigMapper.JobDefinition> jobDefs = definitions.get(0).jobs();
        assertEquals(2, jobDefs.size());
        assertEquals("compile", jobDefs.get(0).name());
        assertEquals(PipelineJob.JobType.BUILD, jobDefs.get(0).jobType());
        assertEquals("package", jobDefs.get(1).name());
        assertEquals(PipelineJob.JobType.PACKAGE, jobDefs.get(1).jobType());
    }

    @Test
    void resolveJobTypeShouldReturnCorrectType() {
        assertEquals(PipelineJob.JobType.BUILD, mapper.resolveJobType("BUILD"));
        assertEquals(PipelineJob.JobType.TEST, mapper.resolveJobType("TEST"));
        assertEquals(PipelineJob.JobType.SCAN, mapper.resolveJobType("SCAN"));
        assertEquals(PipelineJob.JobType.DEPLOY, mapper.resolveJobType("DEPLOY"));
        assertEquals(PipelineJob.JobType.PACKAGE, mapper.resolveJobType("PACKAGE"));
        assertEquals(PipelineJob.JobType.CUSTOM, mapper.resolveJobType("CUSTOM"));
        assertEquals(PipelineJob.JobType.BUILD, mapper.resolveJobType("build"));
        assertEquals(PipelineJob.JobType.TEST, mapper.resolveJobType("Test"));
    }

    @Test
    void resolveJobTypeShouldDefaultToCustomForNull() {
        assertEquals(PipelineJob.JobType.CUSTOM, mapper.resolveJobType(null));
    }

    @Test
    void resolveJobTypeShouldDefaultToCustomForUnknown() {
        assertEquals(PipelineJob.JobType.CUSTOM, mapper.resolveJobType("UNKNOWN"));
        assertEquals(PipelineJob.JobType.CUSTOM, mapper.resolveJobType("RANDOM"));
        assertEquals(PipelineJob.JobType.CUSTOM, mapper.resolveJobType(""));
        assertEquals(PipelineJob.JobType.CUSTOM, mapper.resolveJobType("  "));
    }

    @Test
    void toStageDefinitionsShouldHandleNullStages() {
        PipelineConfig config = new PipelineConfig("pipeline", "desc", null);

        List<PipelineConfigMapper.StageDefinition> definitions = mapper.toStageDefinitions(config);

        assertNotNull(definitions);
        assertTrue(definitions.isEmpty());
    }

    @Test
    void toStageDefinitionsShouldForwardStageDependsOn() {
        StageConfig build = createStage("build", List.of(createJob("compile", "BUILD")));
        StageConfig test = createStage("test", List.of(createJob("unit-test", "TEST")));
        test.setDependsOn(List.of("build"));
        PipelineConfig config = new PipelineConfig("pipeline", "desc", List.of(build, test));

        List<PipelineConfigMapper.StageDefinition> definitions = mapper.toStageDefinitions(config);

        assertEquals(2, definitions.size());
        assertEquals(List.of(), definitions.get(0).dependsOn());
        assertEquals(List.of("build"), definitions.get(1).dependsOn());
    }

    @Test
    void toStageDefinitionsShouldForwardJobDependsOn() {
        JobConfig compile = createJob("compile", "BUILD");
        JobConfig unitTest = createJob("unit-test", "TEST");
        unitTest.setDependsOn(List.of("compile"));
        StageConfig stage = createStage("build", List.of(compile, unitTest));
        PipelineConfig config = new PipelineConfig("pipeline", "desc", List.of(stage));

        List<PipelineConfigMapper.StageDefinition> definitions = mapper.toStageDefinitions(config);

        List<PipelineConfigMapper.JobDefinition> jobDefs = definitions.get(0).jobs();
        assertEquals(2, jobDefs.size());
        assertEquals(List.of(), jobDefs.get(0).dependsOn());
        assertEquals(List.of("compile"), jobDefs.get(1).dependsOn());
    }

    @Test
    void toStageDefinitionsShouldHandleNullDependsOnGracefully() {
        StageConfig stage = new StageConfig("build");
        stage.setJobs(List.of(createJob("compile", "BUILD")));
        stage.setDependsOn(null);
        PipelineConfig config = new PipelineConfig("pipeline", "desc", List.of(stage));

        List<PipelineConfigMapper.StageDefinition> definitions = mapper.toStageDefinitions(config);

        assertEquals(1, definitions.size());
        assertEquals(List.of(), definitions.get(0).dependsOn());
        assertEquals(List.of(), definitions.get(0).jobs().get(0).dependsOn());
    }

    private StageConfig createStage(String name, List<JobConfig> jobs) {
        StageConfig stage = new StageConfig(name);
        stage.setJobs(jobs);
        return stage;
    }

    private JobConfig createJob(String name, String type) {
        return new JobConfig(name, type);
    }
}
