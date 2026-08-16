package com.cicd.platform.worker.pipeline;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.exception.PipelineConfigurationException;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the pipeline YAML inside the checked-out repository.
 *
 * <p>Path traversal is impossible here because the candidates are relative
 * paths resolved against the repository root and validated by
 * {@link PipelineParser} (the repo itself is untrusted but confined to the
 * isolated workspace).</p>
 */
@Component
public class PipelineLoader {

    private final WorkerProperties props;

    public PipelineLoader(WorkerProperties props) {
        this.props = props;
    }

    public Path locate(Path repositoryRoot, String requestedFile) {
        if (requestedFile != null && !requestedFile.isBlank()) {
            Path candidate = safeResolve(repositoryRoot, requestedFile);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            throw new PipelineConfigurationException(
                    "Pipeline file '" + requestedFile + "' not found in repository");
        }
        List<String> candidates = new ArrayList<>(props.getPipelineFileCandidates());
        if (props.getPipelineFile() != null && !props.getPipelineFile().isBlank()) {
            candidates.add(0, props.getPipelineFile());
        }
        for (String candidateName : candidates) {
            Path candidate = safeResolve(repositoryRoot, candidateName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new PipelineConfigurationException(
                "No pipeline file found. Looked for: " + String.join(", ", candidates));
    }

    private Path safeResolve(Path repositoryRoot, String relative) {
        Path normalized = repositoryRoot.resolve(relative).normalize();
        if (!normalized.startsWith(repositoryRoot.normalize())) {
            throw new PipelineConfigurationException("Invalid pipeline file path: " + relative);
        }
        return normalized;
    }
}
