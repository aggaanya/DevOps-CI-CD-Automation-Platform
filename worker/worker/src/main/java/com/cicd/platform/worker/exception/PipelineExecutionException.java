package com.cicd.platform.worker.exception;

/**
 * Raised when pipeline orchestration fails unexpectedly (not because a
 * workload step returned a non-zero exit code, which is a normal result).
 */
public class PipelineExecutionException extends WorkerException {

    public PipelineExecutionException(String message) {
        super(message);
    }

    public PipelineExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
