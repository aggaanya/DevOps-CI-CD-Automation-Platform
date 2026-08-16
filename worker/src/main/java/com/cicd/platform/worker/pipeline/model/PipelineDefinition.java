package com.cicd.platform.worker.pipeline.model;

import java.util.List;

/**
 * Immutable in-memory representation of the parsed pipeline YAML.
 *
 * @param name    pipeline name.
 * @param stages  ordered stages.
 * @param source  human-readable source location of the YAML file.
 */
public record PipelineDefinition(String name, List<StageDefinition> stages, String source) {

    public PipelineDefinition {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }
}
