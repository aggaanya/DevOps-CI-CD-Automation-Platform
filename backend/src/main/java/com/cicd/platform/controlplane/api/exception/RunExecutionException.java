package com.cicd.platform.controlplane.api.exception;

public class RunExecutionException extends RuntimeException {

    public RunExecutionException(String message) {
        super(message);
    }

    public RunExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
