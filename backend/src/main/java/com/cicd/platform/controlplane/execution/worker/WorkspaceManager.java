package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final WorkspaceConfig workspaceConfig;

    public WorkspaceManager(WorkspaceConfig workspaceConfig) {
        this.workspaceConfig = workspaceConfig;
    }

    public Path createWorkspace(UUID runId, UUID jobId) {
        Path basePath = Path.of(workspaceConfig.getBasePath()).toAbsolutePath().normalize();
        Path workspacePath = basePath
                .resolve(runId.toString())
                .resolve(jobId.toString())
                .toAbsolutePath()
                .normalize();

        if (!workspacePath.startsWith(basePath)) {
            throw new SecurityException("Path traversal detected: " + workspacePath);
        }

        try {
            Path workDir = workspacePath.resolve("work");
            Path logsDir = workspacePath.resolve("logs");
            Path artifactsDir = workspacePath.resolve(workspaceConfig.getArtifactDir());

            Files.createDirectories(workspacePath);
            Files.createDirectories(workDir);
            Files.createDirectories(logsDir);
            Files.createDirectories(artifactsDir);

            log.info("Created workspace at {} for run {} job {}", workspacePath, runId, jobId);
        } catch (IOException e) {
            log.error("Failed to create workspace at {}", workspacePath, e);
            throw new RuntimeException("Failed to create workspace", e);
        }

        return workspacePath;
    }

    public Path getWorkDir(Path workspacePath) {
        return workspacePath.resolve("work");
    }

    public Path getLogsDir(Path workspacePath) {
        return workspacePath.resolve("logs");
    }

    public Path getArtifactsDir(Path workspacePath) {
        return workspacePath.resolve(workspaceConfig.getArtifactDir());
    }

    public void cleanupWorkspace(Path workspacePath) {
        Path basePath = Path.of(workspaceConfig.getBasePath()).toAbsolutePath().normalize();
        Path normalized = workspacePath.toAbsolutePath().normalize();

        if (!normalized.startsWith(basePath)) {
            log.warn("Refusing to cleanup path outside workspace base: {}", workspacePath);
            return;
        }

        if (Files.exists(normalized)) {
            try {
                Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
                log.info("Cleaned up workspace at {}", workspacePath);
            } catch (IOException e) {
                log.error("Failed to cleanup workspace at {}", workspacePath, e);
            }
        }
    }

    public void cleanupRunWorkspaces(UUID runId) {
        Path basePath = Path.of(workspaceConfig.getBasePath()).toAbsolutePath().normalize();
        Path runPath = basePath.resolve(runId.toString()).toAbsolutePath().normalize();

        if (!runPath.startsWith(basePath)) {
            log.warn("Refusing to cleanup run path outside workspace base: {}", runPath);
            return;
        }

        if (Files.exists(runPath)) {
            try {
                Files.walkFileTree(runPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
                log.info("Cleaned up all workspaces for run {}", runId);
            } catch (IOException e) {
                log.error("Failed to cleanup run workspaces for run {}", runId, e);
            }
        }
    }
}
