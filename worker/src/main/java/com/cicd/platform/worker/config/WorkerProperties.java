package com.cicd.platform.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker configuration, fully externalised through environment variables.
 */
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    private String id = "worker-" + System.getProperty("user.name", "local").toLowerCase();
    private int maxConcurrency = 2;
    private Path workspaceRoot = Path.of(System.getProperty("java.io.tmpdir"), "cicd-workspaces");
    private long commandTimeoutMs = 15 * 60 * 1000L;
    private long maxPipelineDurationMs = 30 * 60 * 1000L;
    private String defaultBranch = "main";
    private String pipelineFile = "pipeline.yml";
    private long maxLogBytes = 1024 * 1024;
    private boolean retryEnabled = true;
    private int maxRetries = 3;
    private long retryDelayMs = 30_000L;
    private String commandPolicy = "STRICT";
    private boolean buildImageEnabled = false;
    private List<String> pipelineFileCandidates = List.of(
            "pipeline.yml", ".cicd/pipeline.yml", "pipeline.yaml", ".cicd/pipeline.yaml");
    private Map<String, String> baseEnvironment = new HashMap<>();
    private long staleWorkspaceMaxAgeHours = 24L;
    private long maxProcessTreeKillWaitMs = 5_000L;

    private final Rabbit rabbit = new Rabbit();
    private final Git git = new Git();
    private final Sandbox sandbox = new Sandbox();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public Path getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(Path workspaceRoot) { this.workspaceRoot = workspaceRoot; }

    public long getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(long commandTimeoutMs) { this.commandTimeoutMs = commandTimeoutMs; }

    public long getMaxPipelineDurationMs() { return maxPipelineDurationMs; }
    public void setMaxPipelineDurationMs(long maxPipelineDurationMs) { this.maxPipelineDurationMs = maxPipelineDurationMs; }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public String getPipelineFile() { return pipelineFile; }
    public void setPipelineFile(String pipelineFile) { this.pipelineFile = pipelineFile; }

    public long getMaxLogBytes() { return maxLogBytes; }
    public void setMaxLogBytes(long maxLogBytes) { this.maxLogBytes = maxLogBytes; }

    public boolean isRetryEnabled() { return retryEnabled; }
    public void setRetryEnabled(boolean retryEnabled) { this.retryEnabled = retryEnabled; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }

    public String getCommandPolicy() { return commandPolicy; }
    public void setCommandPolicy(String commandPolicy) { this.commandPolicy = commandPolicy; }

    public boolean isBuildImageEnabled() { return buildImageEnabled; }
    public void setBuildImageEnabled(boolean buildImageEnabled) { this.buildImageEnabled = buildImageEnabled; }

    public List<String> getPipelineFileCandidates() { return pipelineFileCandidates; }
    public void setPipelineFileCandidates(List<String> pipelineFileCandidates) {
        this.pipelineFileCandidates = pipelineFileCandidates == null ? List.of() : pipelineFileCandidates;
    }

    public Map<String, String> getBaseEnvironment() { return baseEnvironment; }
    public void setBaseEnvironment(Map<String, String> baseEnvironment) {
        this.baseEnvironment = baseEnvironment == null ? new HashMap<>() : baseEnvironment;
    }

    public long getStaleWorkspaceMaxAgeHours() { return staleWorkspaceMaxAgeHours; }
    public void setStaleWorkspaceMaxAgeHours(long staleWorkspaceMaxAgeHours) {
        this.staleWorkspaceMaxAgeHours = staleWorkspaceMaxAgeHours;
    }

    public long getMaxProcessTreeKillWaitMs() { return maxProcessTreeKillWaitMs; }
    public void setMaxProcessTreeKillWaitMs(long maxProcessTreeKillWaitMs) {
        this.maxProcessTreeKillWaitMs = maxProcessTreeKillWaitMs;
    }

    public Rabbit getRabbit() { return rabbit; }
    public Git getGit() { return git; }
    public Sandbox getSandbox() { return sandbox; }

    public static class Rabbit {
        private String jobsExchange = "cicd.jobs.exchange";
        private String resultsExchange = "cicd.results.exchange";
        private String jobRoutingKey = "cicd.job.submitted";
        private String delayRoutingKey = "cicd.job.delay";
        private String deadRoutingKey = "cicd.job.dead";
        private String resultRoutingKey = "cicd.result";
        private String jobQueue = "cicd.jobs";
        private String delayQueue = "cicd.jobs.delay";
        private String deadLetterQueue = "cicd.jobs.dlq";

        public String getJobsExchange() { return jobsExchange; }
        public void setJobsExchange(String jobsExchange) { this.jobsExchange = jobsExchange; }
        public String getResultsExchange() { return resultsExchange; }
        public void setResultsExchange(String resultsExchange) { this.resultsExchange = resultsExchange; }
        public String getJobRoutingKey() { return jobRoutingKey; }
        public void setJobRoutingKey(String jobRoutingKey) { this.jobRoutingKey = jobRoutingKey; }
        public String getDelayRoutingKey() { return delayRoutingKey; }
        public void setDelayRoutingKey(String delayRoutingKey) { this.delayRoutingKey = delayRoutingKey; }
        public String getDeadRoutingKey() { return deadRoutingKey; }
        public void setDeadRoutingKey(String deadRoutingKey) { this.deadRoutingKey = deadRoutingKey; }
        public String getResultRoutingKey() { return resultRoutingKey; }
        public void setResultRoutingKey(String resultRoutingKey) { this.resultRoutingKey = resultRoutingKey; }
        public String getJobQueue() { return jobQueue; }
        public void setJobQueue(String jobQueue) { this.jobQueue = jobQueue; }
        public String getDelayQueue() { return delayQueue; }
        public void setDelayQueue(String delayQueue) { this.delayQueue = delayQueue; }
        public String getDeadLetterQueue() { return deadLetterQueue; }
        public void setDeadLetterQueue(String deadLetterQueue) { this.deadLetterQueue = deadLetterQueue; }
    }

    public static class Git {
        private long cloneTimeoutMs = 5 * 60 * 1000L;
        private String username = "";
        private String password = "";
        private String token = "";
        private boolean failOnInvalidRepository = true;

        public long getCloneTimeoutMs() { return cloneTimeoutMs; }
        public void setCloneTimeoutMs(long cloneTimeoutMs) { this.cloneTimeoutMs = cloneTimeoutMs; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public boolean isFailOnInvalidRepository() { return failOnInvalidRepository; }
        public void setFailOnInvalidRepository(boolean failOnInvalidRepository) {
            this.failOnInvalidRepository = failOnInvalidRepository;
        }
    }

    public static class Sandbox {
        private String mode = "process";
        private String dockerImage = "maven:3.9-eclipse-temurin-17";
        private String dockerNetwork = "";
        private String runAsUser = "";
        private String containerWorkspacePath = "/workspace";
        private long dockerPullTimeoutMs = 10 * 60 * 1000L;
        private String dockerMemoryLimit = "512m";
        private int dockerCpuCount = 1;
        private boolean dockerReadOnlyRoot = true;
        private boolean dockerNoInternet = false;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getDockerImage() { return dockerImage; }
        public void setDockerImage(String dockerImage) { this.dockerImage = dockerImage; }
        public String getDockerNetwork() { return dockerNetwork; }
        public void setDockerNetwork(String dockerNetwork) { this.dockerNetwork = dockerNetwork; }
        public String getRunAsUser() { return runAsUser; }
        public void setRunAsUser(String runAsUser) { this.runAsUser = runAsUser; }
        public String getContainerWorkspacePath() { return containerWorkspacePath; }
        public void setContainerWorkspacePath(String containerWorkspacePath) {
            this.containerWorkspacePath = containerWorkspacePath;
        }
        public long getDockerPullTimeoutMs() { return dockerPullTimeoutMs; }
        public void setDockerPullTimeoutMs(long dockerPullTimeoutMs) { this.dockerPullTimeoutMs = dockerPullTimeoutMs; }

        public String getDockerMemoryLimit() { return dockerMemoryLimit; }
        public void setDockerMemoryLimit(String dockerMemoryLimit) { this.dockerMemoryLimit = dockerMemoryLimit; }

        public int getDockerCpuCount() { return dockerCpuCount; }
        public void setDockerCpuCount(int dockerCpuCount) { this.dockerCpuCount = dockerCpuCount; }

        public boolean isDockerReadOnlyRoot() { return dockerReadOnlyRoot; }
        public void setDockerReadOnlyRoot(boolean dockerReadOnlyRoot) { this.dockerReadOnlyRoot = dockerReadOnlyRoot; }

        public boolean isDockerNoInternet() { return dockerNoInternet; }
        public void setDockerNoInternet(boolean dockerNoInternet) { this.dockerNoInternet = dockerNoInternet; }
    }
}
