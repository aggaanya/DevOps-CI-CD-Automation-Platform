package com.cicd.platform.worker.sandbox;

import java.nio.file.Path;
import java.util.Map;

/**
 * A validated command execution request.
 *
 * @param workspaceHost           absolute host path of the job workspace.
 * @param workingDirectoryHost    absolute host directory the command runs in.
 * @param workingDirectoryRelative relative path from the repo root ("." or "backend").
 * @param command                 raw command executed by the sandbox shell.
 * @param environment             sanitised environment (already allow-listed).
 * @param timeoutMs               hard timeout before forced termination.
 * @param jobId                   owning job (for termination bookkeeping).
 */
public record SandboxRequest(
        Path workspaceHost,
        Path workingDirectoryHost,
        String workingDirectoryRelative,
        String command,
        Map<String, String> environment,
        long timeoutMs,
        String jobId) {
}
