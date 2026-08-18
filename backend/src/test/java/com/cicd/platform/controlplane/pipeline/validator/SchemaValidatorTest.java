package com.cicd.platform.controlplane.pipeline.validator;

import com.cicd.platform.controlplane.pipeline.config.JobConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidatorTest {

    private SchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaValidator();
    }

    @Test
    void validConfigShouldPass() {
        PipelineConfig config = createValidConfig();

        PipelineValidationResult result = validator.validate(config);

        assertTrue(result.isValid());
    }

    @Test
    void missingPipelineNameShouldFail() {
        PipelineConfig config = createValidConfig();
        config.setName(null);

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.name") && e.code().equals("REQUIRED")));
    }

    @Test
    void blankPipelineNameShouldFail() {
        PipelineConfig config = createValidConfig();
        config.setName("   ");

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.name") && e.code().equals("REQUIRED")));
    }

    @Test
    void pipelineNameTooLongShouldFail() {
        PipelineConfig config = createValidConfig();
        config.setName("a".repeat(256));

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.name") && e.code().equals("SIZE")));
    }

    @Test
    void emptyStagesShouldFail() {
        PipelineConfig config = createValidConfig();
        config.setStages(new ArrayList<>());

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.stages") && e.code().equals("REQUIRED")));
    }

    @Test
    void missingStageNameShouldFail() {
        PipelineConfig config = createValidConfig();
        config.getStages().get(0).setName(null);

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.stages[0].name") && e.code().equals("REQUIRED")));
    }

    @Test
    void stageWithNoJobsShouldFail() {
        PipelineConfig config = createValidConfig();
        config.getStages().get(0).setJobs(new ArrayList<>());

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.stages[0].jobs") && e.code().equals("REQUIRED")));
    }

    @Test
    void missingJobNameShouldFail() {
        PipelineConfig config = createValidConfig();
        config.getStages().get(0).getJobs().get(0).setName(null);

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.stages[0].jobs[0].name") && e.code().equals("REQUIRED")));
    }

    @Test
    void invalidJobTypeShouldFail() {
        PipelineConfig config = createValidConfig();
        config.getStages().get(0).getJobs().get(0).setType("INVALID_TYPE");

        PipelineValidationResult result = validator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.path().equals("pipeline.stages[0].jobs[0].type") && e.code().equals("INVALID")));
    }

    @Test
    void validJobTypesShouldPass() {
        List<String> validTypes = List.of("BUILD", "TEST", "SCAN", "DEPLOY", "PACKAGE", "CUSTOM");

        for (String type : validTypes) {
            PipelineConfig config = createValidConfig();
            config.getStages().get(0).getJobs().get(0).setType(type);

            PipelineValidationResult result = validator.validate(config);

            assertTrue(result.isValid(), "Job type '" + type + "' should be valid");
        }
    }

    private PipelineConfig createValidConfig() {
        JobConfig job = new JobConfig("compile", "BUILD");

        StageConfig stage = new StageConfig("build");
        stage.setJobs(List.of(job));

        PipelineConfig config = new PipelineConfig("test-pipeline", "A test pipeline", List.of(stage));
        return config;
    }
}
