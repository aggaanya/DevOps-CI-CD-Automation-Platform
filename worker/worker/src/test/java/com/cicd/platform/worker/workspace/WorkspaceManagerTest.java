package com.cicd.platform.worker.workspace;

import com.cicd.platform.worker.TestData;
import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.exception.WorkspaceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private WorkerProperties props;
    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        props = new WorkerProperties();
        props.setWorkspaceRoot(tempDir);
        manager = new WorkspaceManager(props);
    }

    @Test
    void createsIsolatedWorkspaceLayout() {
        Workspace workspace = manager.create(TestData.validJob());
        assertTrue(Files.isDirectory(workspace.repoDir()));
        assertTrue(Files.isDirectory(workspace.logsDir()));
        assertTrue(Files.isDirectory(workspace.artifactsDir()));
        assertTrue(workspace.root().startsWith(tempDir));
    }

    @Test
    void cleanupRemovesWorkspace() {
        Workspace workspace = manager.create(TestData.validJob());
        assertTrue(Files.exists(workspace.root()));
        manager.cleanup(workspace);
        assertFalse(Files.exists(workspace.root()));
    }

    @Test
    void rejectsPathTraversalJobId() {
        PipelineJob malicious = new PipelineJob("../escape", "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb", "main", null, null, null, null);
        assertThrows(WorkspaceException.class, () -> manager.create(malicious));
    }

    @Test
    void rejectsNullJobId() {
        PipelineJob malicious = new PipelineJob(null, "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb", "main", null, null, null, null);
        assertThrows(WorkspaceException.class, () -> manager.create(malicious));
    }

    @Test
    void cleanupIgnoresNullWorkspace() {
        manager.cleanup(null);
    }

    @Test
    void workspaceRootCreatedOnInit() throws IOException {
        Path nestedRoot = tempDir.resolve("deep/nested/root");
        props.setWorkspaceRoot(nestedRoot);
        WorkspaceManager manager2 = new WorkspaceManager(props);
        manager2.init();
        assertTrue(Files.isDirectory(nestedRoot));
    }
}
