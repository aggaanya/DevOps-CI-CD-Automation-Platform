package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.ExecutionContext;
import com.cicd.platform.controlplane.execution.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    public StepResult executeCommand(Path workDir, String command, long timeoutSeconds) {
        Instant startedAt = Instant.now();
        Path logFile = null;

        try {
            logFile = Files.createTempFile(workDir, "step-log-", ".txt");

            ProcessBuilder processBuilder;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                processBuilder = new ProcessBuilder("cmd", "/c", command);
            } else {
                processBuilder = new ProcessBuilder("sh", "-c", command);
            }
            processBuilder.directory(workDir.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            Process process = processBuilder.start();

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);

                String stderr = "Command timed out after " + timeoutSeconds + " seconds";
                return StepResult.failure(
                        "timeout",
                        -1,
                        stderr,
                        startedAt,
                        Instant.now());
            }

            int exitCode = process.exitValue();
            String stdout = Files.readString(logFile);

            return new StepResult(
                    command,
                    exitCode == 0,
                    exitCode,
                    stdout,
                    "",
                    startedAt,
                    Instant.now());

        } catch (IOException e) {
            log.error("Failed to execute command: {}", command, e);
            return StepResult.failure(
                    "io-error",
                    -1,
                    e.getMessage(),
                    startedAt,
                    Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while executing command: {}", command, e);
            return StepResult.failure(
                    "interrupted",
                    -1,
                    "Execution interrupted",
                    startedAt,
                    Instant.now());
        } finally {
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public StepResult executeStep(ExecutionContext ctx, String stepName, String command) {
        if (ctx == null) {
            log.info("Executing step '{}' (no context)", stepName);
            Path workDir = Path.of(System.getProperty("java.io.tmpdir"));
            StepResult result = executeCommand(workDir, command, 3600);
            if (result.success()) {
                log.info("Step '{}' completed successfully (exitCode={})", stepName, result.exitCode());
            } else {
                log.warn("Step '{}' failed (exitCode={}, stderr={})", stepName, result.exitCode(), result.stderr());
            }
            return result;
        }

        log.info("Executing step '{}' for job '{}' (attempt {})",
                stepName, ctx.jobName(), ctx.attemptNumber());

        StepResult result = executeCommand(ctx.workDir(), command, ctx.timeoutSeconds());

        if (result.success()) {
            log.info("Step '{}' completed successfully for job '{}' (exitCode={})",
                    stepName, ctx.jobName(), result.exitCode());
        } else {
            log.warn("Step '{}' failed for job '{}' (exitCode={}, stderr={})",
                    stepName, ctx.jobName(), result.exitCode(), result.stderr());
        }

        return result;
    }
}
