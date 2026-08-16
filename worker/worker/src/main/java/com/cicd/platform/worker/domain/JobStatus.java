package com.cicd.platform.worker.domain;

/**
 * Execution status of a pipeline, stage, job or step.
 */
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
