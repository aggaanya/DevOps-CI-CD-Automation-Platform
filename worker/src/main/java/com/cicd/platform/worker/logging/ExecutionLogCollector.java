package com.cicd.platform.worker.logging;

import com.cicd.platform.worker.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Collects the execution log of a single job in memory (bounded) and mirrors
 * it into the workspace {@code logs/} directory for inspection.
 */
public class ExecutionLogCollector {

    private static final int CAPACITY = 512 * 1024;

    private final StringBuilder buffer = new StringBuilder();
    private final Path logFile;

    public ExecutionLogCollector(Workspace workspace) {
        this.logFile = workspace.logsDir().resolve("job.log");
    }

    public synchronized void log(String line) {
        appendToBuffer(line);
        appendToFile(line);
    }

    public synchronized void appendOutput(String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        String[] lines = output.split("\n");
        for (String line : lines) {
            appendToBuffer(line);
        }
        appendToFile(output);
    }

    public synchronized String getLog() {
        return buffer.toString();
    }

    public Path getLogFile() {
        return logFile;
    }

    private void appendToBuffer(String line) {
        if (buffer.length() < CAPACITY) {
            buffer.append(line).append(System.lineSeparator());
        } else if (buffer.length() < CAPACITY + 64) {
            buffer.append("[execution log truncated]").append(System.lineSeparator());
        }
    }

    private void appendToFile(String line) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // logging must never break execution
        }
    }
}
