package com.cicd.platform.controlplane.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "execution.workspace")
public class WorkspaceConfig {

    private String basePath = "workspace";
    private long timeoutSeconds = ExecutionConstants.DEFAULT_JOB_TIMEOUT_SECONDS;
    private int maxRetries = ExecutionConstants.DEFAULT_MAX_RETRIES;
    private boolean retryEnabled = true;
    private String workerId = ExecutionConstants.DEFAULT_WORKER_ID;
    private String artifactDir = ExecutionConstants.WORKSPACE_ARTIFACTS_DIR;
    private int concurrency = ExecutionConstants.DEFAULT_CONCURRENCY;
    private int prefetch = ExecutionConstants.DEFAULT_PREFETCH;

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public boolean isRetryEnabled() { return retryEnabled; }
    public void setRetryEnabled(boolean retryEnabled) { this.retryEnabled = retryEnabled; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getArtifactDir() { return artifactDir; }
    public void setArtifactDir(String artifactDir) { this.artifactDir = artifactDir; }

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }

    public int getPrefetch() { return prefetch; }
    public void setPrefetch(int prefetch) { this.prefetch = prefetch; }
}
