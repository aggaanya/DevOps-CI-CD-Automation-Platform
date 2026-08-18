package com.cicd.platform.controlplane.pipeline.parser;

import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.PipelineRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;

public class PipelineYamlParser {

    private static final Logger log = LoggerFactory.getLogger(PipelineYamlParser.class);

    private final Yaml yaml;

    public PipelineYamlParser() {
        LoaderOptions options = new LoaderOptions();
        Constructor constructor = new Constructor(PipelineRoot.class, options);
        this.yaml = new Yaml(constructor);
    }

    public PipelineConfig parse(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new PipelineYamlParseException("Pipeline YAML content is empty");
        }

        try {
            PipelineRoot root = yaml.load(yamlContent);
            if (root == null || root.getPipeline() == null) {
                throw new PipelineYamlParseException("YAML must contain a top-level 'pipeline' key");
            }
            return root.getPipeline();
        } catch (PipelineYamlParseException e) {
            throw e;
        } catch (YAMLException e) {
            log.warn("YAML parsing failed: {}", e.getMessage());
            throw new PipelineYamlParseException("Invalid YAML syntax: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error parsing pipeline YAML", e);
            throw new PipelineYamlParseException("Failed to parse pipeline YAML: " + e.getMessage(), e);
        }
    }
}
