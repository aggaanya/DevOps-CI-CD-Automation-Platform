package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.ExecutionContext;
import com.cicd.platform.controlplane.execution.StepResult;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerExecutorTest {

    @TempDir
    Path tempDir;

    @Mock private GitOperations gitOperations;
    @Mock private StepExecutor stepExecutor;
    @Mock private ExecutionLogger executionLogger;
    @InjectMocks private WorkerExecutor workerExecutor;

    private ExecutionContext buildCtx(String jobType, Path workDir) {
        Path logsDir = tempDir.resolve("logs");
        Path artifactsDir = tempDir.resolve("artifacts");
        try {
            Files.createDirectories(logsDir);
            Files.createDirectories(artifactsDir);
        } catch (Exception ignored) {
        }
        return new ExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), "test-job",
                PipelineJob.JobType.valueOf(jobType),
                tempDir, workDir,
                logsDir, artifactsDir,
                "https://github.com/org/repo.git", "main", "abc123",
                1, 3600, "worker-1"
        );
    }

    @Test
    void executeJob_gitInitFailed_returnsFalse() throws Exception {
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        ExecutionContext ctx = buildCtx("BUILD", workDir);

        when(gitOperations.initializeWorkspace(any(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        boolean result = workerExecutor.executeJob(ctx);

        assertFalse(result);
        verify(executionLogger).logJobStart(ctx);
        verify(executionLogger).logError(eq("Failed to initialize workspace"), any());
    }

    @Test
    void executeJob_buildCommandDetected_executesMaven() throws Exception {
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve("pom.xml"), "<project/>");
        ExecutionContext ctx = buildCtx("BUILD", workDir);

        when(gitOperations.initializeWorkspace(any(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(stepExecutor.executeStep(any(), anyString(), anyString()))
                .thenReturn(StepResult.success("BUILD", "BUILD SUCCESS", Instant.now(), Instant.now()));

        boolean result = workerExecutor.executeJob(ctx);

        assertTrue(result);
        verify(stepExecutor).executeStep(eq(ctx), eq("BUILD"), contains("mvn"));
        verify(executionLogger).logJobComplete(ctx, true, 0);
    }

    @Test
    void executeJob_stepFailed_returnsFalse() throws Exception {
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        ExecutionContext ctx = buildCtx("BUILD", workDir);

        when(gitOperations.initializeWorkspace(any(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(stepExecutor.executeStep(any(), anyString(), anyString()))
                .thenReturn(StepResult.failure("BUILD", 1, "error", Instant.now(), Instant.now()));

        boolean result = workerExecutor.executeJob(ctx);

        assertFalse(result);
        verify(executionLogger).logJobComplete(ctx, false, 1);
    }

    @Test
    void executeJob_noGitUrl_skipsGitInit() throws Exception {
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        Path logsDir = tempDir.resolve("logs");
        Path artifactsDir = tempDir.resolve("artifacts");
        Files.createDirectories(logsDir);
        Files.createDirectories(artifactsDir);
        ExecutionContext ctx = new ExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), "test-job",
                PipelineJob.JobType.BUILD,
                tempDir, workDir,
                logsDir, artifactsDir,
                null, "main", "abc123",
                1, 3600, "worker-1"
        );

        when(stepExecutor.executeStep(any(), anyString(), anyString()))
                .thenReturn(StepResult.success("BUILD", "ok", Instant.now(), Instant.now()));

        boolean result = workerExecutor.executeJob(ctx);

        assertTrue(result);
        verify(gitOperations, never()).initializeWorkspace(any(), any(), any(), any());
    }

    @Test
    void executeJob_exceptionThrown_returnsFalse() throws Exception {
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        ExecutionContext ctx = buildCtx("BUILD", workDir);

        when(gitOperations.initializeWorkspace(any(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("unexpected"));

        boolean result = workerExecutor.executeJob(ctx);

        assertFalse(result);
        verify(executionLogger).logError(eq("Job execution failed"), any());
    }
}
