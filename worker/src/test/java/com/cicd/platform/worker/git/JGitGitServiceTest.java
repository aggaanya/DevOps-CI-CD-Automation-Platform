package com.cicd.platform.worker.git;

import com.cicd.platform.worker.TestData;
import com.cicd.platform.worker.TestGitRepo;
import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.exception.GitOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JGitGitServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void checksOutExactCommit() throws Exception {
        Path repoDir = tempDir.resolve("repo-src");
        String sha = TestGitRepo.createMavenRepo(repoDir, true);

        JGitGitService gitService = new JGitGitService(new WorkerProperties());
        Path checkout = Files.createTempDirectory(tempDir, "checkout");

        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                repoDir.toUri().toString(), sha, "main", "pipeline.yml", null, null, null);

        CommitInfo info = gitService.checkoutCommit(job, checkout);

        assertEquals(sha, info.commitSha());
        assertTrue(Files.exists(checkout.resolve("pom.xml")));
        assertTrue(Files.exists(checkout.resolve("pipeline.yml")));
    }

    @Test
    void rejectsNonExistentCommit() throws Exception {
        Path repoDir = tempDir.resolve("repo-src");
        TestGitRepo.createMavenRepo(repoDir, true);

        JGitGitService gitService = new JGitGitService(new WorkerProperties());
        Path checkout = Files.createTempDirectory(tempDir, "checkout");

        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                repoDir.toUri().toString(), "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                "main", "pipeline.yml", null, null, null);

        assertThrows(GitOperationException.class, () -> gitService.checkoutCommit(job, checkout));
    }

    @Test
    void rejectsInvalidSha() throws Exception {
        Path repoDir = tempDir.resolve("repo-src");
        String sha = TestGitRepo.createMavenRepo(repoDir, true);

        JGitGitService gitService = new JGitGitService(new WorkerProperties());
        Path checkout = Files.createTempDirectory(tempDir, "checkout");

        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                repoDir.toUri().toString(), "not-a-valid-sha", "main", "pipeline.yml", null, null, null);

        assertThrows(GitOperationException.class, () -> gitService.checkoutCommit(job, checkout));
    }

    @Test
    void supportsPublicGitHubUrls() {
        PipelineJob job = TestData.validJob();
        assertEquals("https://github.com/aggaanya/RealShield-Deepfake-Detection-Digital-Identity-Verifier.git",
                job.repositoryUrl());
    }
}
