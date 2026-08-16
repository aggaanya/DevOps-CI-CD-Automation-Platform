package com.cicd.platform.worker.service;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.exception.PipelineJobValidationException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structural validation of an inbound job message. Rejects malformed or
 * malicious messages before any resource is provisioned.
 */
@Component
public class PipelineJobValidator {

    private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern PIPELINE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{7,64}$");
    private static final Pattern ENV_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private final WorkerProperties props;

    public PipelineJobValidator(WorkerProperties props) {
        this.props = props;
    }

    public void validate(PipelineJob job) {
        if (job == null) {
            throw new PipelineJobValidationException("Job message is empty");
        }
        requireValid(job.jobId(), JOB_ID, "jobId");
        requireValid(job.pipelineId(), PIPELINE_ID, "pipelineId");
        requireValidRepository(job.repositoryUrl());
        requireValid(job.commitSha(), SHA, "commitSha");
        if (job.branch() != null && !job.branch().isBlank()
                && !job.branch().matches("^[A-Za-z0-9._/\\-]{1,128}$")) {
            throw new PipelineJobValidationException("Invalid branch '" + job.branch() + "'");
        }
        if (job.environment() != null) {
            for (Map.Entry<String, String> entry : job.environment().entrySet()) {
                if (!ENV_KEY.matcher(entry.getKey()).matches()) {
                    throw new PipelineJobValidationException(
                            "Invalid environment variable name '" + entry.getKey() + "'");
                }
                if (containsSecretName(entry.getKey())) {
                    throw new PipelineJobValidationException(
                            "Environment variable '" + entry.getKey() + "' is not allowed on jobs");
                }
                if (entry.getValue() != null && (entry.getValue().length() > 1024
                        || containsControlChars(entry.getValue()))) {
                    throw new PipelineJobValidationException(
                            "Invalid environment variable value for '" + entry.getKey() + "'");
                }
            }
        }
        if (job.pipelineFile() != null && !job.pipelineFile().isBlank()
                && job.pipelineFile().length() > 256) {
            throw new PipelineJobValidationException("pipelineFile is too long");
        }
    }

    private void requireValid(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new PipelineJobValidationException("Invalid " + field + " value");
        }
    }

    private void requireValidRepository(String url) {
        if (url == null || url.isBlank()) {
            throw new PipelineJobValidationException("repositoryUrl is required");
        }
        if (url.length() > 2048) {
            throw new PipelineJobValidationException("repositoryUrl is too long");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                throw new PipelineJobValidationException("repositoryUrl must be an absolute URL");
            }
            switch (scheme.toLowerCase()) {
                case "http", "https", "git", "ssh", "file" -> {
                    // supported
                }
                default -> throw new PipelineJobValidationException(
                        "repositoryUrl scheme '" + scheme + "' is not allowed");
            }
        } catch (IllegalArgumentException e) {
            throw new PipelineJobValidationException("repositoryUrl is not a valid URL");
        }
    }

    private boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSecretName(String key) {
        String upper = key.toUpperCase();
        String[] blocked = {"TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "PRIVATE_KEY",
                "AWS_ACCESS_KEY", "AWS_SECRET", "API_KEY", "CLIENT_SECRET", "GITHUB_TOKEN", "AUTHORIZATION"};
        for (String fragment : blocked) {
            if (upper.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
