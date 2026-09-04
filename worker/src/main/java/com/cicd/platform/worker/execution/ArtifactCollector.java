package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.domain.ArtifactInfo;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies declared build artifacts from the workspace into
 * {@code workspace/artifacts/<jobName>/} after a successful job.
 */
@Component
public class ArtifactCollector {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCollector.class);

    public List<String> collect(Workspace workspace, JobDefinition job) {
        if (job.artifacts().isEmpty()) {
            return List.of();
        }
        Path repo = workspace.repoDir();
        Path destination = workspace.artifactsDir().resolve(sanitize(job.name()));
        List<String> collected = new ArrayList<>();
        for (String pattern : job.artifacts()) {
            collectPattern(repo, destination, pattern, collected);
        }
        return collected;
    }

    private void collectPattern(Path repo, Path destination, String pattern, List<String> collected) {
        Path normalized = repo.resolve(pattern).normalize();
        if (!normalized.startsWith(repo.normalize())) {
            log.warn("Artifact pattern escapes repo root: {}", pattern);
            return;
        }
        Path startDir = normalized.getParent();
        String fileNamePattern = normalized.getFileName() == null ? "*" : normalized.getFileName().toString();
        if (startDir == null || !Files.isDirectory(startDir)) {
            return;
        }
        if (fileNamePattern.contains("*")) {
            try (Stream<Path> files = Files.list(startDir)) {
                files.filter(p -> matchesGlob(p.getFileName().toString(), fileNamePattern))
                        .filter(Files::isRegularFile)
                        .forEach(p -> copy(p, destination, collected));
            } catch (IOException e) {
                log.debug("Artifact glob failed: {}", e.getMessage());
            }
        } else if (Files.isRegularFile(normalized)) {
            copy(normalized, destination, collected);
        }
    }

    private void copy(Path source, Path destination, List<String> collected) {
        try {
            Files.createDirectories(destination);
            Path target = destination.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(target);
            collected.add(target.getFileName() + " (" + size + " bytes)");
        } catch (IOException e) {
            log.debug("Artifact copy failed for {}: {}", source, e.getMessage());
        }
    }

    private boolean matchesGlob(String name, String glob) {
        String regex = globToRegex(glob);
        return name.matches(regex);
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '.':
                    regex.append("\\.");
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '[':
                    regex.append("[");
                    break;
                case ']':
                    regex.append("]");
                    break;
                default:
                    if ("+()^${}|.\\".indexOf(c) >= 0) {
                        regex.append("\\");
                    }
                    regex.append(c);
                    break;
            }
        }
        return regex.toString();
    }

    private String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
