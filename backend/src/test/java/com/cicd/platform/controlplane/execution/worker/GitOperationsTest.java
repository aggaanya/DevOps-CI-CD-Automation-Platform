package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.StepResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitOperationsTest {

    private final StepExecutor stepExecutor = new StepExecutor();
    private final GitOperations gitOperations = new GitOperations(stepExecutor);

    @Test
    void sanitizeUrl_removesPassword() {
        String sanitized = GitOperations.sanitizeUrl("https://user:secret123@github.com/org/repo.git");
        assertEquals("https://user:***@github.com/org/repo.git", sanitized);
    }

    @Test
    void sanitizeUrl_removesToken() {
        String sanitized = GitOperations.sanitizeUrl("https://oauth2:ghp_abc123@github.com/org/repo.git");
        assertEquals("https://oauth2:***@github.com/org/repo.git", sanitized);
    }

    @Test
    void sanitizeUrl_noCredentials_returnsUnchanged() {
        String url = "https://github.com/org/repo.git";
        assertEquals(url, GitOperations.sanitizeUrl(url));
    }

    @Test
    void sanitizeUrl_null_returnsNull() {
        assertNull(GitOperations.sanitizeUrl(null));
    }

    @Test
    void sanitizeUrl_empty_returnsEmpty() {
        assertEquals("", GitOperations.sanitizeUrl(""));
    }

    @Test
    void sanitizeUrl_sshUrl_unchanged() {
        String sshUrl = "git@github.com:org/repo.git";
        assertEquals(sshUrl, GitOperations.sanitizeUrl(sshUrl));
    }

    @Test
    void initializeWorkspace_nullGitUrl_returnsTrue() {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"));

        boolean result = gitOperations.initializeWorkspace(workDir, null, "main", "abc123");

        assertTrue(result);
    }

    @Test
    void initializeWorkspace_blankGitUrl_returnsTrue() {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"));

        boolean result = gitOperations.initializeWorkspace(workDir, "", "main", "abc123");

        assertTrue(result);
    }

    @Test
    void initializeWorkspace_nullWorkDir_returnsFalse() {
        boolean result = gitOperations.initializeWorkspace(null, "https://github.com/org/repo.git", "main", "abc123");

        assertFalse(result);
    }

    @Test
    void initializeWorkspace_matchingCommit_returnsTrue() {
        StepExecutor mockExecutor = mock(StepExecutor.class);
        GitOperations ops = new GitOperations(mockExecutor);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"));
        Instant now = Instant.now();

        when(mockExecutor.executeCommand(eq(workDir), contains("clone"), eq(600L)))
                .thenReturn(StepResult.success("clone", "", now, now));
        when(mockExecutor.executeCommand(eq(workDir), contains("checkout"), eq(60L)))
                .thenReturn(StepResult.success("checkout", "", now, now));
        when(mockExecutor.executeCommand(eq(workDir), contains("rev-parse"), eq(10L)))
                .thenReturn(StepResult.success("rev-parse", "abc123def456789012345678901234567890abcd\n", now, now));

        boolean result = ops.initializeWorkspace(workDir, "https://github.com/org/repo.git", "main", "abc123");

        assertTrue(result);
    }

    @Test
    void initializeWorkspace_mismatchingCommit_returnsFalse() {
        StepExecutor mockExecutor = mock(StepExecutor.class);
        GitOperations ops = new GitOperations(mockExecutor);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"));
        Instant now = Instant.now();

        when(mockExecutor.executeCommand(eq(workDir), contains("clone"), eq(600L)))
                .thenReturn(StepResult.success("clone", "", now, now));
        when(mockExecutor.executeCommand(eq(workDir), contains("checkout"), eq(60L)))
                .thenReturn(StepResult.success("checkout", "", now, now));
        when(mockExecutor.executeCommand(eq(workDir), contains("rev-parse"), eq(10L)))
                .thenReturn(StepResult.success("rev-parse", "deadbeef00000000000000000000000000000000", now, now));

        boolean result = ops.initializeWorkspace(workDir, "https://github.com/org/repo.git", "main", "abc123");

        assertFalse(result);
    }

    @Test
    void initializeWorkspace_revParseFails_returnsFalse() {
        StepExecutor mockExecutor = mock(StepExecutor.class);
        GitOperations ops = new GitOperations(mockExecutor);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"));
        Instant now = Instant.now();

        when(mockExecutor.executeCommand(eq(workDir), contains("clone"), eq(600L)))
                .thenReturn(StepResult.success("clone", "", now, now));
        when(mockExecutor.executeCommand(eq(workDir), contains("checkout"), eq(60L)))
                .thenReturn(StepResult.success("checkout", "", now, now));
        when(mockExecutor.executeCommand(eq(workDir), contains("rev-parse"), eq(10L)))
                .thenReturn(StepResult.failure("rev-parse", 128, "not a git repository", now, now));

        boolean result = ops.initializeWorkspace(workDir, "https://github.com/org/repo.git", "main", "abc123");

        assertFalse(result);
    }
}
