package com.cicd.platform.controlplane.execution;

import java.util.regex.Pattern;

public final class ExecutionInputValidator {

    private static final Pattern BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-/]*$");

    private static final Pattern COMMIT_SHA_PATTERN = Pattern.compile("^[0-9a-fA-F]{6,64}$");

    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-]+$");

    private static final Pattern GIT_URL_PATTERN = Pattern.compile("^https?://[^\\s@]+$");

    private ExecutionInputValidator() {
    }

    public static boolean isValidBranch(String branch) {
        return branch != null
                && !branch.isBlank()
                && branch.length() <= 255
                && BRANCH_PATTERN.matcher(branch).matches();
    }

    public static boolean isValidCommitSha(String commitSha) {
        return commitSha != null
                && !commitSha.isBlank()
                && COMMIT_SHA_PATTERN.matcher(commitSha.strip()).matches();
    }

    public static boolean isValidSafeToken(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 255
                && SAFE_TOKEN_PATTERN.matcher(value).matches();
    }

    public static boolean isValidGitUrl(String gitUrl) {
        return gitUrl != null
                && !gitUrl.isBlank()
                && gitUrl.length() <= 2048
                && GIT_URL_PATTERN.matcher(gitUrl).matches();
    }
}