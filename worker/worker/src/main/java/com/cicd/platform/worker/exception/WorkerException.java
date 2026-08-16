package com.cicd.platform.worker.exception;

/**
 * Marker for worker-internal failures that are not caused by the workload
 * (infrastructure problems such as git clone or workspace provisioning).
 *
 * <p>Messages never include secrets; sensitive values are redacted before
 * the exception is created by the raising layer.</p>
 */
public class WorkerException extends RuntimeException {

    public WorkerException(String message) {
        super(message);
    }

    public WorkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
