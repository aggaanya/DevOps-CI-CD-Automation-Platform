package com.cicd.platform.controlplane.pipeline.parser;

public class PipelineYamlParseException extends RuntimeException {

    public PipelineYamlParseException(String message) {
        super(message);
    }

    public PipelineYamlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
