package com.cicd.platform.controlplane.pipeline.parser;

import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineYamlParserTest {

    private PipelineYamlParser parser;

    private static final String VALID_YAML = """
            pipeline:
              name: test-pipeline
              description: A test pipeline
              stages:
                - name: build
                  jobs:
                    - name: compile
                      type: BUILD
                - name: test
                  dependsOn:
                    - build
                  jobs:
                    - name: unit-test
                      type: TEST
            """;

    @BeforeEach
    void setUp() {
        parser = new PipelineYamlParser();
    }

    @Test
    void parseValidYaml() {
        PipelineConfig config = parser.parse(VALID_YAML);

        assertNotNull(config);
        assertEquals("test-pipeline", config.getName());
        assertEquals("A test pipeline", config.getDescription());
        assertNotNull(config.getStages());
        assertEquals(2, config.getStages().size());

        assertEquals("build", config.getStages().get(0).getName());
        assertEquals(1, config.getStages().get(0).getJobs().size());
        assertEquals("compile", config.getStages().get(0).getJobs().get(0).getName());
        assertEquals("BUILD", config.getStages().get(0).getJobs().get(0).getType());

        assertEquals("test", config.getStages().get(1).getName());
        assertEquals(1, config.getStages().get(1).getDependsOn().size());
        assertEquals("build", config.getStages().get(1).getDependsOn().get(0));
        assertEquals("unit-test", config.getStages().get(1).getJobs().get(0).getName());
        assertEquals("TEST", config.getStages().get(1).getJobs().get(0).getType());
    }

    @Test
    void parseBlankYaml() {
        assertThrows(PipelineYamlParseException.class, () -> parser.parse(""));
        assertThrows(PipelineYamlParseException.class, () -> parser.parse("   "));
        assertThrows(PipelineYamlParseException.class, () -> parser.parse("  \t\n  "));
    }

    @Test
    void parseNullYaml() {
        assertThrows(PipelineYamlParseException.class, () -> parser.parse(null));
    }

    @Test
    void parseMissingPipelineKey() {
        String yaml = """
                stages:
                  - name: build
                    jobs:
                      - name: compile
                        type: BUILD
                """;
        PipelineYamlParseException ex = assertThrows(PipelineYamlParseException.class, () -> parser.parse(yaml));
        assertNotNull(ex.getMessage());
    }

    @Test
    void parseInvalidYamlSyntax() {
        assertThrows(PipelineYamlParseException.class, () -> parser.parse("[{"));
    }

    @Test
    void parseEmptyPipelineKey() {
        String yaml = "pipeline: ";
        PipelineYamlParseException ex = assertThrows(PipelineYamlParseException.class, () -> parser.parse(yaml));
        assertNotNull(ex.getMessage());
    }
}
