package com.cicd.platform.worker.pipeline;

import com.cicd.platform.worker.exception.PipelineConfigurationException;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.PipelineDefinition;
import com.cicd.platform.worker.pipeline.model.StageDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineValidatorTest {

    private PipelineValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PipelineValidator();
    }

    private PipelineDefinition pipeline(String stageName, String jobName) {
        StepDefinition step = new StepDefinition(StepType.RUN, "echo", "echo hello", null);
        JobDefinition job = new JobDefinition(jobName, null, null, Map.of(),
                List.of(step), List.of());
        return new PipelineDefinition("p", List.of(new StageDefinition(stageName, List.of(job))), "test");
    }

    @Test
    void acceptsValidPipeline() {
        assertDoesNotThrow(() -> validator.validate(pipeline("stage-1", "job-1")));
    }

    @Test
    void rejectsDuplicateStageNames() {
        PipelineDefinition p = new PipelineDefinition("p",
                List.of(new StageDefinition("dup", List.of(
                        new JobDefinition("j1", null, null, Map.of(),
                                List.of(new StepDefinition(StepType.RUN, "a", "echo a", null)), List.of()))),
                        new StageDefinition("dup", List.of(
                                new JobDefinition("j2", null, null, Map.of(),
                                        List.of(new StepDefinition(StepType.RUN, "b", "echo b", null)), List.of())))),
                "test");
        assertThrows(PipelineConfigurationException.class, () -> validator.validate(p));
    }

    @Test
    void rejectsDuplicateJobNames() {
        PipelineDefinition p = new PipelineDefinition("p", List.of(new StageDefinition("s",
                List.of(new JobDefinition("j", null, null, Map.of(),
                                List.of(new StepDefinition(StepType.RUN, "a", "echo a", null)), List.of()),
                        new JobDefinition("j", null, null, Map.of(),
                                List.of(new StepDefinition(StepType.RUN, "b", "echo b", null)), List.of())))),
                "test");
        assertThrows(PipelineConfigurationException.class, () -> validator.validate(p));
    }

    @Test
    void rejectsWorkingDirectoryEscape() {
        StepDefinition step = new StepDefinition(StepType.RUN, "a", "echo a", null);
        JobDefinition job = new JobDefinition("j", "../outside", null, Map.of(), List.of(step), List.of());
        PipelineDefinition p = new PipelineDefinition("p", List.of(new StageDefinition("s", List.of(job))), "test");
        assertThrows(PipelineConfigurationException.class, () -> validator.validate(p));
    }

    @Test
    void rejectsSecretEnvKey() {
        StepDefinition step = new StepDefinition(StepType.RUN, "a", "echo a", null);
        JobDefinition job = new JobDefinition("j", null, null,
                Map.of("GITHUB_TOKEN", "x"), List.of(step), List.of());
        PipelineDefinition p = new PipelineDefinition("p", List.of(new StageDefinition("s", List.of(job))), "test");
        assertThrows(PipelineConfigurationException.class, () -> validator.validate(p));
    }

    @Test
    void rejectsInvalidNameCharacters() {
        assertThrows(PipelineConfigurationException.class,
                () -> validator.validate(pipeline("bad name!", "job-1")));
    }

    @Test
    void rejectsEmptyCommand() {
        StepDefinition step = new StepDefinition(StepType.RUN, "a", "   ", null);
        JobDefinition job = new JobDefinition("j", null, null, Map.of(), List.of(step), List.of());
        PipelineDefinition p = new PipelineDefinition("p", List.of(new StageDefinition("s", List.of(job))), "test");
        assertThrows(PipelineConfigurationException.class, () -> validator.validate(p));
    }

    @Test
    void rejectsCommandWithNewline() {
        StepDefinition step = new StepDefinition(StepType.RUN, "a", "echo hi\nrm -rf /", null);
        JobDefinition job = new JobDefinition("j", null, null, Map.of(), List.of(step), List.of());
        PipelineDefinition p = new PipelineDefinition("p", List.of(new StageDefinition("s", List.of(job))), "test");
        assertThrows(PipelineConfigurationException.class, () -> validator.validate(p));
    }

    @Test
    void rejectsCommandTooLong() {
        StepDefinition step = new StepDefinition(StepType.RUN, "a", "x".repeat(5000), null);
        JobDefinition job = new JobDefinition("j", null, null, Map.of(), List.of(step), List.of());
        PipelineDefinition p = new PipelineDefinition("p", List.of(new StageDefinition("s", List.of(job))), "test");
        assertThrows(PipelineConfigurationException.class, () -> validator.validate(p));
    }
}
