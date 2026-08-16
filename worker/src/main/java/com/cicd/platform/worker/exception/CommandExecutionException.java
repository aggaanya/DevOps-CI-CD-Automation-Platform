package com.cicd.platform.worker.exception;

/**
 * Raised when a command cannot be started or its streams cannot be read.
 * A non-zero exit code is NOT an exception; it is captured in the result.
 */
public class CommandExecutionException extends WorkerException {

    public CommandExecutionException(String message) {
        super(message);
    }

    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
