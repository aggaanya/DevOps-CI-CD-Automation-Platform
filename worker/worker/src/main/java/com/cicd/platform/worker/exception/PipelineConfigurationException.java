package com.cicd.platform.worker.exception;

/**
 * Raised when the pipeline YAML cannot be found, parsed or validated.
 * Treated as a permanent job failure: a result is published and the
 * message is acknowledged.
 */
public class PipelineConfigurationException extends WorkerException {

    public PipelineConfigurationException(String message) {
        super(message);
    }

    public PipelineConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
