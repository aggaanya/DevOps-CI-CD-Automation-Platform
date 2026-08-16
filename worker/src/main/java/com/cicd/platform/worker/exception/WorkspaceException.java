package com.cicd.platform.worker.exception;

/**
 * Raised when the isolated workspace cannot be created or cleaned up.
 */
public class WorkspaceException extends WorkerException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
