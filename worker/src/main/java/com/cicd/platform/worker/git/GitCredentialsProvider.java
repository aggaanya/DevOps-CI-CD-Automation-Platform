package com.cicd.platform.worker.git;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Resolves git credentials for private repositories. Anonymous by default.
 *
 * <p>Credentials are never logged. The API intentionally accepts a token or
 * username/password pair so a future GitHub App / OAuth integration can
 * plug in a different {@link CredentialsProvider} without changing the
 * {@link GitService} contract.</p>
 */
public class GitCredentialsProvider {

    private final String username;
    private final String password;
    private final String token;

    public GitCredentialsProvider(String username, String password, String token) {
        this.username = username;
        this.password = password;
        this.token = token;
    }

    public boolean isPresent() {
        return token != null && !token.isBlank()
                || username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public CredentialsProvider resolve() {
        if (token != null && !token.isBlank()) {
            return new UsernamePasswordCredentialsProvider(token, "");
        }
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            return new UsernamePasswordCredentialsProvider(username, password);
        }
        return null;
    }
}
