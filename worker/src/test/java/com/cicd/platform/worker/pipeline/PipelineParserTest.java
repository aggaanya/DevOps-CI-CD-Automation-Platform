package com.cicd.platform.worker.pipeline;

import com.cicd.platform.worker.exception.PipelineConfigurationException;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.PipelineDefinition;
import com.cicd.platform.worker.pipeline.model.StageDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineParserTest {

    private PipelineParser parser;

    @BeforeEach
    void setUp() {
        parser = new PipelineParser(new ObjectMapper(new YAMLFactory()));
    }

    @Test
    void parsesPhase3Structure() {
        String yaml = """
                pipeline:
                  name: realshield-api
                  stages:
                    - name: build-test
                      jobs:
                        - name: java-build
                          workingDirectory: backend
                          steps:
                            - run: mvn -B clean package
                    - name: package
                      jobs:
                        - name: docker-build
                          steps:
                            - buildImage: realshield-api
                """;
        PipelineDefinition pipeline = parser.parseContent(yaml, "pipeline.yml");
        assertEquals("realshield-api", pipeline.name());
        assertEquals(2, pipeline.stages().size());

        StageDefinition buildTest = pipeline.stages().get(0);
        assertEquals("build-test", buildTest.name());
        JobDefinition javaBuild = buildTest.jobs().get(0);
        assertEquals("java-build", javaBuild.name());
        assertEquals("backend", javaBuild.workingDirectory());
        assertEquals(1, javaBuild.steps().size());
        StepDefinition step = javaBuild.steps().get(0);
        assertEquals(StepType.RUN, step.type());
        assertEquals("mvn -B clean package", step.command());

        StageDefinition packageStage = pipeline.stages().get(1);
        assertEquals(StepType.BUILD_IMAGE, packageStage.jobs().get(0).steps().get(0).type());
        assertEquals("realshield-api", packageStage.jobs().get(0).steps().get(0).command());
    }

    @Test
    void parsesEnvAndArtifacts() {
        String yaml = """
                pipeline:
                  name: demo
                  stages:
                    - name: s1
                      jobs:
                        - name: j1
                          env:
                            FOO: bar
                          artifacts:
                            - backend/target/*.jar
                          steps:
                            - run: echo $FOO
                """;
        PipelineDefinition pipeline = parser.parseContent(yaml, "pipeline.yml");
        JobDefinition job = pipeline.stages().get(0).jobs().get(0);
        assertEquals("bar", job.env().get("FOO"));
        assertEquals(1, job.artifacts().size());
    }

    @Test
    void rejectsMissingPipelineSection() {
        assertThrows(PipelineConfigurationException.class,
                () -> parser.parseContent("name: foo", "pipeline.yml"));
    }

    @Test
    void rejectsMissingPipelineName() {
        String yaml = """
                pipeline:
                  stages:
                    - name: s1
                      jobs:
                        - name: j1
                          steps:
                            - run: echo hi
                """;
        assertThrows(PipelineConfigurationException.class,
                () -> parser.parseContent(yaml, "pipeline.yml"));
    }

    @Test
    void rejectsUnknownStepType() {
        String yaml = """
                pipeline:
                  name: demo
                  stages:
                    - name: s1
                      jobs:
                        - name: j1
                          steps:
                            - downloadArtifact: something
                """;
        assertThrows(PipelineConfigurationException.class,
                () -> parser.parseContent(yaml, "pipeline.yml"));
    }

    @Test
    void rejectsEmptyRunCommand() {
        String yaml = """
                pipeline:
                  name: demo
                  stages:
                    - name: s1
                      jobs:
                        - name: j1
                          steps:
                            - run: "   "
                """;
        assertThrows(PipelineConfigurationException.class,
                () -> parser.parseContent(yaml, "pipeline.yml"));
    }

    @Test
    void rejectsEmptyStages() {
        String yaml = """
                pipeline:
                  name: demo
                  stages:
                """;
        assertThrows(PipelineConfigurationException.class,
                () -> parser.parseContent(yaml, "pipeline.yml"));
    }

    @Test
    void rejectsInvalidYaml() {
        assertThrows(PipelineConfigurationException.class,
                () -> parser.parseContent("::: not yaml", "pipeline.yml"));
    }

    @Test
    void derivesStepNameFromCommand() {
        String yaml = """
                pipeline:
                  name: demo
                  stages:
                    - name: s1
                      jobs:
                        - name: j1
                          steps:
                            - run: mvn -B clean package
                """;
        StepDefinition step = parser.parseContent(yaml, "pipeline.yml")
                .stages().get(0).jobs().get(0).steps().get(0);
        assertTrue(step.name().startsWith("mvn"));
    }
}
