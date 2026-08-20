package com.cicd.platform.controlplane.execution;

import org.slf4j.MDC;

import java.util.UUID;

public final class ExecutionMdc {

    public static final String RUN_ID = "runId";
    public static final String STAGE_ID = "stageId";
    public static final String JOB_ID = "jobId";
    public static final String ATTEMPT_ID = "attemptId";
    public static final String WORKER_ID = "workerId";

    private ExecutionMdc() {}

    public static void setRunId(UUID runId) {
        put(RUN_ID, runId);
    }

    public static void setStageId(UUID stageId) {
        put(STAGE_ID, stageId);
    }

    public static void setJobId(UUID jobId) {
        put(JOB_ID, jobId);
    }

    public static void setAttemptId(UUID attemptId) {
        put(ATTEMPT_ID, attemptId);
    }

    public static void setWorkerId(String workerId) {
        put(WORKER_ID, workerId);
    }

    public static void clearRunId() {
        MDC.remove(RUN_ID);
    }

    public static void clearStageId() {
        MDC.remove(STAGE_ID);
    }

    public static void clearJobId() {
        MDC.remove(JOB_ID);
    }

    public static void clearAttemptId() {
        MDC.remove(ATTEMPT_ID);
    }

    public static void clearWorkerId() {
        MDC.remove(WORKER_ID);
    }

    public static void clearAll() {
        MDC.remove(RUN_ID);
        MDC.remove(STAGE_ID);
        MDC.remove(JOB_ID);
        MDC.remove(ATTEMPT_ID);
        MDC.remove(WORKER_ID);
    }

    private static void put(String key, Object value) {
        if (value != null) {
            MDC.put(key, value.toString());
        }
    }
}
