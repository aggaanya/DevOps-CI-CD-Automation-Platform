package com.cicd.platform.worker.logging;

import org.slf4j.MDC;

/**
 * Structured logging context keys propagated to every log line of a job.
 * Never populated with secrets.
 */
public final class MdcContext {

    public static final String WORKER_ID = "workerId";
    public static final String JOB_ID = "jobId";
    public static final String PIPELINE_ID = "pipelineId";
    public static final String REPOSITORY = "repository";
    public static final String COMMIT_SHA = "commitSha";
    public static final String STAGE = "stage";
    public static final String JOB = "job";
    public static final String STEP = "step";

    private MdcContext() {
    }

    public static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    public static void putJob(String workerId, String jobId, String pipelineId, String repository, String commitSha) {
        put(WORKER_ID, workerId);
        put(JOB_ID, jobId);
        put(PIPELINE_ID, pipelineId);
        put(REPOSITORY, redact(repository));
        put(COMMIT_SHA, commitSha);
    }

    public static void putStageJobStep(String stage, String job, String step) {
        put(STAGE, stage);
        put(JOB, job);
        put(STEP, step);
    }

    public static void remove(String key) {
        MDC.remove(key);
    }

    public static void clear() {
        MDC.remove(WORKER_ID);
        MDC.remove(JOB_ID);
        MDC.remove(PIPELINE_ID);
        MDC.remove(REPOSITORY);
        MDC.remove(COMMIT_SHA);
        MDC.remove(STAGE);
        MDC.remove(JOB);
        MDC.remove(STEP);
    }

    private static String redact(String repository) {
        if (repository == null) {
            return null;
        }
        return repository.replaceAll("(https?://)([^@/]+)@", "$1<redacted>@");
    }
}
