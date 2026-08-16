package com.cicd.platform.worker.exception;

/**
 * Raised when a git operation (clone, fetch, checkout, verify) fails.
 * Classified as a transient/infrastructure failure and eligible for retry.
 */
public class GitOperationException extends WorkerException {

    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
