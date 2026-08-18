package com.cicd.platform.controlplane.pipeline.validator;

import com.cicd.platform.controlplane.pipeline.config.JobConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticValidatorTest {

    private SemanticValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SemanticValidator();
    }

    @Test
    void uniqueNamesShouldPass() {
        PipelineConfig config = createConfigWithUniqueNames();

        PipelineValidationResult result = validator.validate(config);

        assertTrue(result.isValid());
    }

    @Test
    void duplicateStageNamesShouldFail() {
        StageConfig stage1 = createStage("build", List.of(createJob("compile", "BUILD")));
        StageConfig stage2 = createStage("build", List.of(createJob("jar", "PACKAGE")));
        PipelineConfig config = new PipelineConfig("test-pipeline", "desc", List.of(stage1, stage2));

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.code().equals("DUPLICATE") && e.message().contains("Duplicate stage name")));
    }

    @Test
    void duplicateJobNamesInStageShouldFail() {
        JobConfig job1 = createJob("compile", "BUILD");
        JobConfig job2 = createJob("compile", "TEST");
        StageConfig stage = createStage("build", List.of(job1, job2));
        PipelineConfig config = new PipelineConfig("test-pipeline", "desc", List.of(stage));

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.code().equals("DUPLICATE") && e.message().contains("Duplicate job name")));
    }

    @Test
    void duplicateJobNamesInDifferentStagesShouldPass() {
        StageConfig stage1 = createStage("build", List.of(createJob("compile", "BUILD")));
        StageConfig stage2 = createStage("test", List.of(createJob("compile", "TEST")));
        PipelineConfig config = new PipelineConfig("test-pipeline", "desc", List.of(stage1, stage2));

        PipelineValidationResult result = validator.validate(config);

        assertTrue(result.isValid());
    }

    @Test
    void invalidStageDependencyShouldFail() {
        StageConfig stage1 = createStage("build", List.of(createJob("compile", "BUILD")));
        stage1.setDependsOn(List.of("nonexistent"));
        StageConfig stage2 = createStage("deploy", List.of(createJob("publish", "DEPLOY")));
        PipelineConfig config = new PipelineConfig("test-pipeline", "desc", List.of(stage1, stage2));

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.code().equals("INVALID_REFERENCE")
                        && e.message().contains("unknown stage")));
    }

    @Test
    void validStageDependencyShouldPass() {
        StageConfig build = createStage("build", List.of(createJob("compile", "BUILD")));
        StageConfig test = createStage("test", List.of(createJob("unit-test", "TEST")));
        test.setDependsOn(List.of("build"));
        PipelineConfig config = new PipelineConfig("test-pipeline", "desc", List.of(build, test));

        PipelineValidationResult result = validator.validate(config);

        assertTrue(result.isValid());
    }

    @Test
    void invalidJobDependencyShouldFail() {
        JobConfig job1 = createJob("compile", "BUILD");
        JobConfig job2 = createJob("package", "PACKAGE");
        job2.setDependsOn(List.of("nonexistent"));
        StageConfig stage = createStage("build", List.of(job1, job2));
        PipelineConfig config = new PipelineConfig("test-pipeline", "desc", List.of(stage));

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.code().equals("INVALID_REFERENCE")
                        && e.message().contains("unknown job")));
    }

    @Test
    void validJobDependencyShouldPass() {
        JobConfig compile = createJob("compile", "BUILD");
        JobConfig test = createJob("unit-test", "TEST");
        test.setDependsOn(List.of("compile"));
        StageConfig stage = createStage("build", List.of(compile, test));
        PipelineConfig config = new PipelineConfig("test-pipeline", "desc", List.of(stage));

        PipelineValidationResult result = validator.validate(config);

        assertTrue(result.isValid());
    }

    private PipelineConfig createConfigWithUniqueNames() {
        StageConfig build = createStage("build", List.of(createJob("compile", "BUILD")));
        StageConfig test = createStage("test", List.of(createJob("unit-test", "TEST")));
        test.setDependsOn(List.of("build"));
        return new PipelineConfig("test-pipeline", "desc", List.of(build, test));
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
