package com.cicd.platform.worker.service;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.exception.PipelineJobValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineJobValidatorTest {

    private PipelineJobValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PipelineJobValidator(new WorkerProperties());
    }

    @Test
    void acceptsValidJob() {
        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb94063c659613690fa8d5ba40c24646551",
                "main", "pipeline.yml", Map.of("JAVA_HOME", "/opt/jdk"), null, null);
        assertDoesNotThrow(() -> validator.validate(job));
    }

    @Test
    void acceptsShortCommitSha() {
        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb", "main", null, null, null, null);
        assertDoesNotThrow(() -> validator.validate(job));
    }

    @Test
    void rejectsMissingJobId() {
        PipelineJob job = new PipelineJob(null, "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb", "main", null, null, null, null);
        assertThrows(PipelineJobValidationException.class, () -> validator.validate(job));
    }

    @Test
    void rejectsPathTraversalJobId() {
        PipelineJob job = new PipelineJob("../etc/passwd", "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb", "main", null, null, null, null);
        assertThrows(PipelineJobValidationException.class, () -> validator.validate(job));
    }

    @Test
    void rejectsInvalidCommitSha() {
        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                "https://github.com/org/repo.git", "not-a-sha!", "main", null, null, null, null);
        assertThrows(PipelineJobValidationException.class, () -> validator.validate(job));
    }

    @Test
    void rejectsUnsupportedUrlScheme() {
        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                "ftp://github.com/repo.git", "3c547cb", "main", null, null, null, null);
        assertThrows(PipelineJobValidationException.class, () -> validator.validate(job));
    }

    @Test
    void rejectsRelativeUrl() {
        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                "../repo.git", "3c547cb", "main", null, null, null, null);
        assertThrows(PipelineJobValidationException.class, () -> validator.validate(job));
    }

    @Test
    void rejectsSecretNamedEnvironmentFromBackend() {
        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb", "main", null,
                Map.of("GITHUB_TOKEN", "abc"), null, null);
        assertThrows(PipelineJobValidationException.class, () -> validator.validate(job));
    }

    @Test
    void rejectsEnvironmentWithNewline() {
        PipelineJob job = new PipelineJob("job-1", "pipeline-1",
                "https://github.com/org/repo.git", "3c547cb", "main", null,
                Map.of("FOO", "a\nb"), null, null);
        assertThrows(PipelineJobValidationException.class, () -> validator.validate(job));
    }
}
