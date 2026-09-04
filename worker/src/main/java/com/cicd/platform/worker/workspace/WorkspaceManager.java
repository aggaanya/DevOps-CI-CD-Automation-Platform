package com.cicd.platform.worker.workspace;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.exception.WorkspaceException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Creates and removes the isolated workspace of each pipeline job.
 *
 * <p>Every workspace lives under the configured {@code workspace-root} and is
 * identified by a sanitised job id, so a malicious {@code jobId} can never
 * escape the workspace root (path traversal prevention). Workspaces are always
 * removed after execution; stale workspaces from crashed workers are swept at
 * startup.</p>
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final WorkerProperties props;

    public WorkspaceManager(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(props.getWorkspaceRoot());
        } catch (IOException e) {
            throw new WorkspaceException("Cannot create workspace root " + props.getWorkspaceRoot(), e);
        }
        sweepStaleWorkspaces();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Workspace manager shutting down");
    }

    public Workspace create(PipelineJob job) {
        String safeJobId = sanitizeJobId(job.jobId());
        String safeRunId = job.runId() != null ? sanitizeRunId(job.runId()) : null;

        Path root;
        if (safeRunId != null) {
            root = props.getWorkspaceRoot().resolve("run-" + safeRunId).resolve("job-" + safeJobId);
        } else {
            root = props.getWorkspaceRoot().resolve("job-" + safeJobId);
        }

        try {
            Files.createDirectories(root.resolve("repo"));
            Files.createDirectories(root.resolve("logs"));
            Files.createDirectories(root.resolve("artifacts"));
            log.info("Created workspace {} (runId={})", root, safeRunId);
            return new Workspace(safeJobId, root, root.resolve("repo"), root.resolve("logs"), root.resolve("artifacts"));
        } catch (IOException e) {
            throw new WorkspaceException("Cannot create workspace for job " + safeJobId + ": " + e.getMessage(), e);
        }
    }

    public void cleanup(Workspace workspace) {
        if (workspace == null) {
            return;
        }
        Path root = workspace.root();
        if (!root.startsWith(props.getWorkspaceRoot().toAbsolutePath().normalize())) {
            log.error("Refusing to delete workspace outside root: {}", root);
            return;
        }
        try {
            if (Files.exists(root)) {
                try (Stream<Path> paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}", p);
                        }
                    });
                }
                log.info("Cleaned up workspace {}", root);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up workspace {}: {}", root, e.getMessage());
        }
    }

    private String sanitizeJobId(String jobId) {
        if (jobId == null || !JOB_ID_PATTERN.matcher(jobId).matches()) {
            throw new WorkspaceException(
                    "Invalid jobId '" + jobId + "'. Allowed: 1-128 chars, letters, digits, '.', '_', '-'");
        }
        return jobId;
    }

    private String sanitizeRunId(String runId) {
        if (runId == null || !RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new WorkspaceException(
                    "Invalid runId '" + runId + "'. Allowed: 1-128 chars, letters, digits, '.', '_', '-'");
        }
        return runId;
    }

    private void sweepStaleWorkspaces() {
        Path root = props.getWorkspaceRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        Duration maxAge = Duration.ofHours(props.getStaleWorkspaceMaxAgeHours());
        FileTime cutoff = FileTime.fromMillis(Instant.now().minus(maxAge).toEpochMilli());
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .forEach(p -> {
                        String dirName = p.getFileName().toString();
                        if (dirName.startsWith("job-")) {
                            sweepWorkspace(p, cutoff);
                        } else if (dirName.startsWith("run-")) {
                            sweepRunDirectory(p, cutoff);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to sweep stale workspaces: {}", e.getMessage());
        }
    }

    private void sweepRunDirectory(Path runDir, FileTime cutoff) {
        try (Stream<Path> children = Files.list(runDir)) {
            children.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("job-"))
                    .forEach(p -> sweepWorkspace(p, cutoff));
        } catch (IOException e) {
            log.debug("Cannot list run directory {}: {}", runDir, e.getMessage());
        }
        try {
            if (Files.getLastModifiedTime(runDir).compareTo(cutoff) < 0) {
                boolean empty;
                try (Stream<Path> remaining = Files.list(runDir)) {
                    empty = remaining.findFirst().isEmpty();
                }
                if (empty) {
                    log.warn("Sweeping empty stale run directory {}", runDir);
                    Files.deleteIfExists(runDir);
                }
            }
        } catch (IOException e) {
            log.debug("Cannot inspect run directory {}: {}", runDir, e.getMessage());
        }
    }

    private void sweepWorkspace(Path p, FileTime cutoff) {
        try {
            if (Files.getLastModifiedTime(p).compareTo(cutoff) < 0) {
                log.warn("Sweeping stale workspace {}", p);
                cleanup(new Workspace("sweep", p, p.resolve("repo"), p.resolve("logs"),
                        p.resolve("artifacts")));
            }
        } catch (IOException e) {
            log.debug("Cannot inspect workspace {}: {}", p, e.getMessage());
        }
    }
}
