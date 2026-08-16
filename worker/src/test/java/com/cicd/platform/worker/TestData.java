package com.cicd.platform.worker;

import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.execution.ExecutionContext;
import com.cicd.platform.worker.logging.ExecutionLogCollector;
import com.cicd.platform.worker.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Shared test fixtures.
 */
public final class TestData {

    public static final String REPO_URL =
            "https://github.com/aggaanya/RealShield-Deepfake-Detection-Digital-Identity-Verifier.git";

    private TestData() {
    }

    public static PipelineJob validJob() {
        return new PipelineJob(
                "job-123", "pipeline-123", REPO_URL,
                "3c547cb94063c659613690fa8d5ba40c24646551", "main", "pipeline.yml",
                Map.of("JAVA_HOME", "C:/Program Files/Java/jdk-17"),
                Map.of("requester", "test"), Instant.now());
    }

    public static ExecutionContext context(PipelineJob job) throws IOException {
        Path root = Files.createTempDirectory("cicd-test-ws");
        Workspace workspace = new Workspace(job.jobId(), root,
                root.resolve("repo"), root.resolve("logs"), root.resolve("artifacts"));
        Files.createDirectories(workspace.repoDir());
        Files.createDirectories(workspace.logsDir());
        Files.createDirectories(workspace.artifactsDir());
        return new ExecutionContext(job, workspace, new ExecutionLogCollector(workspace));
    }
}
