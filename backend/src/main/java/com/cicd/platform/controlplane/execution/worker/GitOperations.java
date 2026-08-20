package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GitOperations {

    private static final Logger log = LoggerFactory.getLogger(GitOperations.class);
    private static final Pattern CREDENTIAL_PATTERN =
            Pattern.compile("(https?://[^:]+:)([^@]+)(@.*)");

    private final StepExecutor stepExecutor;

    public GitOperations(StepExecutor stepExecutor) {
        this.stepExecutor = stepExecutor;
    }

    public static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        Matcher matcher = CREDENTIAL_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1) + "***" + matcher.group(3);
        }
        return url;
    }

    public StepResult cloneRepository(Path workDir, String gitUrl, String branch) {
        String safeUrl = sanitizeUrl(gitUrl);
        String command = "git clone --branch " + branch + " --single-branch " + gitUrl + " .";
        log.info("Cloning {} (branch={}) into {}", safeUrl, branch, workDir);
        return stepExecutor.executeCommand(workDir, command, 600);
    }

    public StepResult checkoutCommit(Path workDir, String commitSha) {
        String command = "git checkout " + commitSha;
        return stepExecutor.executeCommand(workDir, command, 60);
    }

    public StepResult verifyCommit(Path workDir) {
        String command = "git rev-parse HEAD";
        return stepExecutor.executeCommand(workDir, command, 10);
    }

    public boolean initializeWorkspace(Path workDir, String gitUrl, String branch, String commitSha) {
        if (gitUrl == null || gitUrl.isBlank()) {
            log.warn("No git URL provided, skipping workspace initialization");
            return true;
        }
        if (workDir == null) {
            log.error("Work directory is null, cannot initialize workspace");
            return false;
        }

        String safeUrl = sanitizeUrl(gitUrl);
        log.info("Initializing workspace in {} from {} branch {}", workDir, safeUrl, branch);

        StepResult cloneResult = cloneRepository(workDir, gitUrl, branch);
        if (!cloneResult.success()) {
            log.error("Git clone failed for {}: {}", safeUrl, cloneResult.stderr());
            return false;
        }

        if (commitSha != null && !commitSha.isBlank()) {
            StepResult checkoutResult = checkoutCommit(workDir, commitSha);
            if (!checkoutResult.success()) {
                log.error("Git checkout failed for commit {}: {}", commitSha, checkoutResult.stderr());
                return false;
            }

            StepResult verifyResult = verifyCommit(workDir);
            if (!verifyResult.success()) {
                log.error("Git rev-parse HEAD failed: {}", verifyResult.stderr());
                return false;
            }

            String actualSha = verifyResult.stdout().strip();
            String expectedSha = commitSha.strip();

            if (actualSha.toLowerCase().startsWith(expectedSha.toLowerCase())) {
                log.info("Git commit verified: {}", actualSha);
            } else {
                log.error("Git commit verification failed: requested={}, actual={}",
                        expectedSha, actualSha);
                return false;
            }
        }

        log.info("Workspace initialized successfully at {}", workDir);
        return true;
    }
}
