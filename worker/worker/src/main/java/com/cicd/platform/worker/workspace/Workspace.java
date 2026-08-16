package com.cicd.platform.worker.workspace;

import java.nio.file.Path;

/**
 * Isolated per-job workspace layout.
 *
 * <pre>
 *   {WORKSPACE_ROOT}/job-{jobId}/
 *       repo/        <- git checkout
 *       logs/        <- per-step log files
 *       artifacts/   <- collected build artifacts
 * </pre>
 */
public record Workspace(String jobId, Path root, Path repoDir, Path logsDir, Path artifactsDir) {
}
