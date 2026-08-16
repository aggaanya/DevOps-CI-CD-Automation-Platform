package com.cicd.platform.worker.exception;

/**
 * Raised when a command exceeds its configured timeout and is forcibly
 * terminated. Mapped to {@code TIMED_OUT} in the job result.
 */
public class CommandTimeoutException extends WorkerException {

    private final long timeoutMs;

    public CommandTimeoutException(String message, long timeoutMs) {
        super(message);
        this.timeoutMs = timeoutMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
