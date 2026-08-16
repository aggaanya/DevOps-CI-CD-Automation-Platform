package com.cicd.platform.worker.security;

import com.cicd.platform.worker.exception.WorkerException;

/**
 * Raised when a command or environment variable violates the execution
 * security policy. The violating step is recorded as FAILED and the job
 * stops (defence in depth: the sandbox is the primary boundary).
 */
public class SecurityViolationException extends WorkerException {

    public SecurityViolationException(String message) {
        super(message);
    }
}
