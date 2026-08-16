package com.cicd.platform.worker.git;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.exception.GitOperationException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JGit-backed {@link GitService}.
 *
 * <p>Strategy: clone with all branches and no checkout, then fetch tags and
 * refs, attempt to resolve the requested SHA, fetch the specific SHA if it is
 * not reachable yet, verify, and finally perform a detached checkout of the
 * exact commit.</p>
 */
@Component
public class JGitGitService implements GitService {

    private static final Logger log = LoggerFactory.getLogger(JGitGitService.class);

    private final WorkerProperties props;
    private final GitCredentialsProvider credentials;

    public JGitGitService(WorkerProperties props) {
        this.props = props;
        this.credentials = new GitCredentialsProvider(
                props.getGit().getUsername(),
                props.getGit().getPassword(),
                props.getGit().getToken());
    }

    @Override
    public CommitInfo checkoutCommit(PipelineJob job, Path targetDir) {
        String url = job.repositoryUrl();
        String requestedSha = normalizeSha(job.commitSha());
        long timeoutMs = props.getGit().getCloneTimeoutMs();
        CredentialsProvider credentialsProvider = credentials.resolve();

        try (Git git = clone(url, targetDir, credentialsProvider, timeoutMs)) {
            Repository repository = git.getRepository();

            fetchTagsAndBranches(git, credentialsProvider, timeoutMs);

            ObjectId commitId = repository.resolve(requestedSha + "^{commit}");
            if (commitId == null) {
                log.info("Commit {} not reachable after clone, attempting direct fetch", requestedSha);
                commitId = tryFetchSpecificSha(git, requestedSha, credentialsProvider, timeoutMs);
            }
            if (commitId == null) {
                throw new GitOperationException(
                        "Commit " + requestedSha + " does not exist in repository " + sanitizedUrl(url));
            }

            git.checkout()
                    .setName(commitId.name())
                    .setForce(true)
                    .setCreateBranch(false)
                    .call();

            ObjectId head = repository.resolve("HEAD^{commit}");
            if (head == null || !head.name().equalsIgnoreCase(commitId.name())) {
                throw new GitOperationException(
                        "Verification failed: expected HEAD " + commitId.name() + " but got "
                                + (head == null ? "null" : head.name()));
            }

            String branch = resolveBranch(repository, job.branch());
            log.info("Checked out commit {} (branch {}) from {}", commitId.name(), branch, sanitizedUrl(url));
            return new CommitInfo(commitId.name(), branch, targetDir.toAbsolutePath());
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException(
                    "Git operation failed for " + sanitizedUrl(url) + ": " + safeMessage(e), e);
        }
    }

    private Git clone(String url, Path targetDir, CredentialsProvider credentialsProvider, long timeoutMs)
            throws Exception {
        try {
            return Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(targetDir.toFile())
                    .setCloneAllBranches(true)
                    .setNoCheckout(true)
                    .setCredentialsProvider(credentialsProvider)
                    .setTimeout((int) TimeUnit.MILLISECONDS.toSeconds(timeoutMs))
                    .call();
        } catch (GitAPIException e) {
            throw new GitOperationException("Clone failed for " + sanitizedUrl(url) + ": " + safeMessage(e), e);
        }
    }

    private void fetchTagsAndBranches(Git git, CredentialsProvider credentialsProvider, long timeoutMs)
            throws GitAPIException {
        git.fetch()
                .setRemote("origin")
                .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                        new RefSpec("+refs/tags/*:refs/tags/*"))
                .setCredentialsProvider(credentialsProvider)
                .setTimeout((int) TimeUnit.MILLISECONDS.toSeconds(timeoutMs))
                .call();
    }

    private ObjectId tryFetchSpecificSha(Git git, String sha, CredentialsProvider credentialsProvider,
                                         long timeoutMs) {
        try {
            git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec(sha + ":refs/cicd/fetched/" + sha))
                    .setCredentialsProvider(credentialsProvider)
                    .setTimeout((int) TimeUnit.MILLISECONDS.toSeconds(timeoutMs))
                    .call();
            return git.getRepository().resolve(sha + "^{commit}");
        } catch (Exception e) {
            log.debug("Direct fetch of {} failed: {}", sha, safeMessage(e));
            return null;
        }
    }

    private String resolveBranch(Repository repository, String requestedBranch) {
        if (requestedBranch == null || requestedBranch.isBlank()) {
            return "detached";
        }
        try {
            Ref ref = repository.findRef("refs/remotes/origin/" + requestedBranch);
            return ref != null ? requestedBranch : "detached";
        } catch (Exception e) {
            return "detached";
        }
    }

    private String normalizeSha(String sha) {
        if (sha == null) {
            throw new GitOperationException("commitSha is required");
        }
        String trimmed = sha.trim().toLowerCase();
        if (!trimmed.matches("^[0-9a-f]{7,40}$")) {
            throw new GitOperationException("commitSha '" + trimmed + "' is not a valid commit hash");
        }
        return trimmed;
    }

    private String sanitizedUrl(String url) {
        if (url == null) {
            return "<null>";
        }
        return url.replaceAll("(https?://)([^@/]+)@", "$1<redacted>@");
    }

    private String safeMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)(token|password|passwd|secret|pwd)=?[^\\s,;]+", "$1=<redacted>");
    }
}
