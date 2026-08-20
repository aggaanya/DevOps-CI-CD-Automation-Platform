package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.config.WorkspaceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private WorkspaceConfig config;
    private WorkspaceManager workspaceManager;

    @BeforeEach
    void setUp() {
        config = new WorkspaceConfig();
        config.setBasePath(tempDir.toString());
        workspaceManager = new WorkspaceManager(config);
    }

    @Test
    void createWorkspace_createsDirectories() {
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        Path workspacePath = workspaceManager.createWorkspace(runId, jobId);

        assertTrue(Files.exists(workspacePath));
        assertTrue(Files.exists(workspaceManager.getWorkDir(workspacePath)));
        assertTrue(Files.exists(workspaceManager.getLogsDir(workspacePath)));
        assertTrue(Files.exists(workspaceManager.getArtifactsDir(workspacePath)));
    }

    @Test
    void createWorkspace_pathTraversal_throwsSecurityException() {
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        config.setBasePath(tempDir.resolve("safe").toString());
        WorkspaceManager safeManager = new WorkspaceManager(config);

        assertDoesNotThrow(() -> safeManager.createWorkspace(runId, jobId));
    }

    @Test
    void cleanupWorkspace_removesDirectory() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        Path workspacePath = workspaceManager.createWorkspace(runId, jobId);
        Path testFile = workspaceManager.getWorkDir(workspacePath).resolve("test.txt");
        Files.writeString(testFile, "hello");

        assertTrue(Files.exists(testFile));

        workspaceManager.cleanupWorkspace(workspacePath);

        assertFalse(Files.exists(workspacePath));
    }

    @Test
    void cleanupRunWorkspaces_removesAllJobDirs() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID job1 = UUID.randomUUID();
        UUID job2 = UUID.randomUUID();

        Path ws1 = workspaceManager.createWorkspace(runId, job1);
        Path ws2 = workspaceManager.createWorkspace(runId, job2);

        assertTrue(Files.exists(ws1));
        assertTrue(Files.exists(ws2));

        workspaceManager.cleanupRunWorkspaces(runId);

        assertFalse(Files.exists(ws1));
        assertFalse(Files.exists(ws2));
    }

    @Test
    void cleanupWorkspace_outsideBasePath_refused() {
        Path outsidePath = Path.of("/some/outside/path");

        assertDoesNotThrow(() -> workspaceManager.cleanupWorkspace(outsidePath));
    }

    @Test
    void getArtifactsDir_returnsArtifactSubdirectory() {
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        Path workspacePath = workspaceManager.createWorkspace(runId, jobId);
        Path artifactsDir = workspaceManager.getArtifactsDir(workspacePath);

        assertEquals(workspacePath.resolve(config.getArtifactDir()), artifactsDir);
    }

    @Test
    void createWorkspace_respectsCustomArtifactDir() {
        config.setArtifactDir("build-output");
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        Path workspacePath = workspaceManager.createWorkspace(runId, jobId);
        Path artifactsDir = workspaceManager.getArtifactsDir(workspacePath);

        assertTrue(Files.exists(artifactsDir));
        assertTrue(artifactsDir.toString().contains("build-output"));
    }
}
