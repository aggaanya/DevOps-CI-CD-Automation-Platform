package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.ExecutionContext;
import com.cicd.platform.controlplane.execution.StepResult;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ExecutionLoggerTest {

    private final ExecutionLogger logger = new ExecutionLogger();

    private ExecutionContext buildCtx() {
        return new ExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), "test-job",
                PipelineJob.JobType.BUILD,
                Path.of("/tmp/ws"), Path.of("/tmp/ws/work"),
                Path.of("/tmp/ws/logs"), Path.of("/tmp/ws/artifacts"),
                "https://user:secret@github.com/org/repo.git", "main", "abc123",
                1, 3600, "worker-1"
        );
    }

    @Test
    void logJobStart_doesNotThrow() {
        assertDoesNotThrow(() -> logger.logJobStart(buildCtx()));
    }

    @Test
    void logJobComplete_success_doesNotThrow() {
        assertDoesNotThrow(() -> logger.logJobComplete(buildCtx(), true, 0));
    }

    @Test
    void logJobComplete_failure_doesNotThrow() {
        assertDoesNotThrow(() -> logger.logJobComplete(buildCtx(), false, 1));
    }

    @Test
    void logStepExecution_success_doesNotThrow() {
        StepResult result = StepResult.success("build", "ok", Instant.now(), Instant.now());
        assertDoesNotThrow(() -> logger.logStepExecution("build", result));
    }

    @Test
    void logStepExecution_failure_doesNotThrow() {
        StepResult result = StepResult.failure("build", 1, "error", Instant.now(), Instant.now());
        assertDoesNotThrow(() -> logger.logStepExecution("build", result));
    }

    @Test
    void logError_doesNotThrow() {
        assertDoesNotThrow(() -> logger.logError("test error", new RuntimeException("test")));
    }

    @Test
    void logCancellation_doesNotThrow() {
        assertDoesNotThrow(() -> logger.logCancellation(UUID.randomUUID()));
    }
}
