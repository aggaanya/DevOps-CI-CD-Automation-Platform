package com.cicd.platform.worker.sandbox;

import com.cicd.platform.worker.domain.CommandResult;
import com.cicd.platform.worker.domain.CommandStatus;
import com.cicd.platform.worker.exception.CommandTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessExecutionSandboxTest {

    @TempDir
    Path tempDir;

    private final ProcessExecutionSandbox sandbox = new ProcessExecutionSandbox(1024 * 1024);

    private SandboxRequest request(String command, long timeoutMs, Path workDir) {
        return new SandboxRequest(tempDir, workDir, ".", command, Map.of(), timeoutMs, "test-job");
    }

    @Test
    void capturesStdoutAndExitCode() throws IOException {
        Path workDir = Files.createTempDirectory(tempDir, "cmd");
        CommandResult result = sandbox.execute(request("echo hello-cicd", 30_000, workDir));
        assertEquals(CommandStatus.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello-cicd"));
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void capturesStderrAndNonZeroExit() throws IOException {
        Path workDir = Files.createTempDirectory(tempDir, "cmd");
        CommandResult result = sandbox.execute(request("echo boom 1>&2 && exit 3", 30_000, workDir));
        assertEquals(CommandStatus.FAILED, result.status());
        assertEquals(3, result.exitCode());
        assertTrue(result.stderr().contains("boom"));
    }

    @Test
    void enforcesTimeout() throws IOException {
        Path workDir = Files.createTempDirectory(tempDir, "cmd");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String longCommand = windows ? "ping -n 20 127.0.0.1" : "sleep 20";
        CommandTimeoutException ex = assertThrows(CommandTimeoutException.class,
                () -> sandbox.execute(request(longCommand, 1_500, workDir)));
        assertTrue(ex.getTimeoutMs() == 1_500);
    }

    @Test
    void runsInDesignatedWorkingDirectory() throws IOException {
        Path workDir = Files.createTempDirectory(tempDir, "cmd");
        Path marker = workDir.resolve("marker.txt");
        Files.writeString(marker, "present");
        CommandResult result = sandbox.execute(request("type marker.txt 2>nul || cat marker.txt", 30_000, workDir));
        if (result.status() == CommandStatus.FAILED) {
            result = sandbox.execute(request("cat marker.txt", 30_000, workDir));
        }
        assertEquals(CommandStatus.SUCCESS, result.status(), "marker should be readable from workdir: " + result.stderr());
    }
}
