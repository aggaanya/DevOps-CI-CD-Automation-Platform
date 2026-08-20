package com.cicd.platform.controlplane.execution.config;

public final class ExecutionConstants {

    private ExecutionConstants() {}

    public static final String JOB_DISPATCH_QUEUE = "pipeline-jobs";
    public static final String JOB_DISPATCH_EXCHANGE = "pipeline-jobs-exchange";
    public static final String JOB_DISPATCH_ROUTING_KEY = "job-dispatch";

    public static final String JOB_RESULT_QUEUE = "pipeline-job-results";
    public static final String JOB_RESULT_EXCHANGE = "pipeline-job-results-exchange";
    public static final String JOB_RESULT_ROUTING_KEY = "job-result";

    public static final long DEFAULT_JOB_TIMEOUT_SECONDS = 3600;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_POLL_INTERVAL_MS = 500;

    public static final String WORKSPACE_BASE_DIR = "workspace";
    public static final String WORKSPACE_WORK_DIR = "work";
    public static final String WORKSPACE_LOGS_DIR = "logs";
    public static final String WORKSPACE_ARTIFACTS_DIR = "artifacts";

    public static final String DEFAULT_WORKER_ID = "worker-local";
    public static final int DEFAULT_CONCURRENCY = 1;
    public static final int DEFAULT_PREFETCH = 1;
}
