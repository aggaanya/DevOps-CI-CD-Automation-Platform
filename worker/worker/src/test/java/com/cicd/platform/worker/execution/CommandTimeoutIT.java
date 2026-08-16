package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.TestGitRepo;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.PipelineResult;
import com.cicd.platform.worker.service.PipelineExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a command exceeding the timeout is forcibly terminated and
 * reported as TIMED_OUT. No RabbitMQ needed: the service is invoked directly.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "worker.retry-enabled=false"
})
class CommandTimeoutIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Path wsRoot = java.nio.file.Files.createTempDirectory("cicd-timeout-ws");
        registry.add("worker.workspace-root", () -> wsRoot.toString());
        registry.add("worker.command-timeout-ms", () -> 1500L);
    }

    @Autowired
    private PipelineExecutionService executionService;

    @TempDir
    Path tempDir;

    @Test
    void commandTimeoutIsReported() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String longCommand = windows ? "ping -n 30 127.0.0.1" : "sleep 30";
        Path repo = tempDir.resolve("repo");
        String sha = TestGitRepo.createShellRepo(repo, longCommand);

        PipelineJob job = new PipelineJob("timeout-job", "timeout-pipeline",
                repo.toUri().toString(), sha, "main", "pipeline.yml", null, null, Instant.now());

        PipelineResult result = executionService.execute(job);

        assertEquals(JobStatus.TIMED_OUT, result.status());
        assertEquals(1, result.stages().size());
        assertEquals(JobStatus.TIMED_OUT, result.stages().get(0).status());
    }
}
