package com.cicd.platform.worker.git;

/**
 * Result of preparing a repository for execution.
 *
 * @param commitSha  full resolved commit SHA that was checked out.
 * @param branch     branch the commit was resolved from.
 * @param repoDir    local checkout directory.
 */
public record CommitInfo(String commitSha, String branch, java.nio.file.Path repoDir) {
}
