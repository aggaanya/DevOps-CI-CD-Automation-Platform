package com.cicd.platform.controlplane.pipeline.validator;

import com.cicd.platform.controlplane.pipeline.config.JobConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyValidatorTest {

    private DependencyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DependencyValidator();
    }

    @Test
    void noDependenciesShouldPass() {
        PipelineConfig config = new PipelineConfig("p", "d", List.of(
                new StageConfig("build"),
                new StageConfig("test")
        ));
        config.getStages().get(0).setJobs(List.of(new JobConfig("compile", "BUILD")));
        config.getStages().get(1).setJobs(List.of(new JobConfig("unit-test", "TEST")));

        PipelineValidationResult result = validator.validate(config);
        assertTrue(result.isValid());
    }

    @Test
    void linearStageDependencyShouldPass() {
        StageConfig build = new StageConfig("build");
        build.setJobs(List.of(new JobConfig("compile", "BUILD")));

        StageConfig test = new StageConfig("test");
        test.setDependsOn(List.of("build"));
        test.setJobs(List.of(new JobConfig("unit-test", "TEST")));

        PipelineConfig config = new PipelineConfig("p", "d", List.of(build, test));

        PipelineValidationResult result = validator.validate(config);
        assertTrue(result.isValid());
    }

    @Test
    void stageCycleShouldFail() {
        StageConfig build = new StageConfig("build");
        build.setDependsOn(List.of("test"));
        build.setJobs(List.of(new JobConfig("compile", "BUILD")));

        StageConfig test = new StageConfig("test");
        test.setDependsOn(List.of("build"));
        test.setJobs(List.of(new JobConfig("unit-test", "TEST")));

        PipelineConfig config = new PipelineConfig("p", "d", List.of(build, test));

        PipelineValidationResult result = validator.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.code().equals("CYCLIC_DEPENDENCY")));
    }

    @Test
    void threeStageCycleShouldFail() {
        StageConfig a = new StageConfig("a");
        a.setDependsOn(List.of("c"));
        a.setJobs(List.of(new JobConfig("a1", "BUILD")));

        StageConfig b = new StageConfig("b");
        b.setDependsOn(List.of("a"));
        b.setJobs(List.of(new JobConfig("b1", "TEST")));

        StageConfig c = new StageConfig("c");
        c.setDependsOn(List.of("b"));
        c.setJobs(List.of(new JobConfig("c1", "SCAN")));

        PipelineConfig config = new PipelineConfig("p", "d", List.of(a, b, c));

        PipelineValidationResult result = validator.validate(config);
        assertFalse(result.isValid());
    }

    @Test
    void jobCycleShouldFail() {
        JobConfig a = new JobConfig("compile", "BUILD");
        a.setDependsOn(List.of("test"));

        JobConfig b = new JobConfig("test", "TEST");
        b.setDependsOn(List.of("compile"));

        StageConfig stage = new StageConfig("build");
        stage.setJobs(List.of(a, b));

        PipelineConfig config = new PipelineConfig("p", "d", List.of(stage));

        PipelineValidationResult result = validator.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.code().equals("CYCLIC_DEPENDENCY")));
    }

    @Test
    void linearJobDependencyShouldPass() {
        JobConfig compile = new JobConfig("compile", "BUILD");
        JobConfig test = new JobConfig("unit-test", "TEST");
        test.setDependsOn(List.of("compile"));

        StageConfig stage = new StageConfig("build");
        stage.setJobs(List.of(compile, test));

        PipelineConfig config = new PipelineConfig("p", "d", List.of(stage));

        PipelineValidationResult result = validator.validate(config);
        assertTrue(result.isValid());
    }
}
