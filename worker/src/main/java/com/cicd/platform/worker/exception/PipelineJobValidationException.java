package com.cicd.platform.worker.exception;

/**
 * Raised when an inbound job message fails structural validation.
 * The message is treated as permanently invalid (dead-lettered).
 */
public class PipelineJobValidationException extends WorkerException {

    public PipelineJobValidationException(String message) {
        super(message);
    }
}
