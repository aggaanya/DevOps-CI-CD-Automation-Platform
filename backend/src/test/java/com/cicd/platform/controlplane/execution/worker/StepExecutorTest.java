package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.StepResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepExecutorTest {

    private final StepExecutor stepExecutor = new StepExecutor();

    @Test
    void executeCommand_success() {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"));
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "cmd /c echo hello" : "echo hello";

        StepResult result = stepExecutor.executeCommand(workDir, cmd, 10);

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
    }

    @Test
    void executeCommand_failure() {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"));
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "cmd /c exit 1" : "exit 1";

        StepResult result = stepExecutor.executeCommand(workDir, cmd, 10);

        assertFalse(result.success());
        assertEquals(1, result.exitCode());
    }
}
