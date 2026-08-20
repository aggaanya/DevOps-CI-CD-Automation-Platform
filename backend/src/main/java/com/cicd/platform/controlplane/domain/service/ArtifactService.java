package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Artifact;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.repository.ArtifactRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ArtifactService {

    private final ArtifactRepository artifactRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineJobRepository pipelineJobRepository;

    public ArtifactService(ArtifactRepository artifactRepository,
                           PipelineRunRepository pipelineRunRepository,
                           PipelineJobRepository pipelineJobRepository) {
        this.artifactRepository = artifactRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineJobRepository = pipelineJobRepository;
    }

    public Artifact create(UUID pipelineRunId, Artifact.ArtifactType artifactType,
                           String name, String locationUrl, UUID jobId) {
        PipelineRun run = pipelineRunRepository.findById(pipelineRunId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineRun not found with id: " + pipelineRunId));

        Artifact artifact = new Artifact(run, artifactType, name, locationUrl);
        if (jobId != null) {
            PipelineJob job = pipelineJobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("PipelineJob not found with id: " + jobId));
            artifact.setJob(job);
        }
        return artifactRepository.save(artifact);
    }

    @Transactional(readOnly = true)
    public Artifact findById(UUID id) {
        return artifactRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Artifact> findByRunId(UUID runId) {
        return artifactRepository.findByPipelineRunId(runId);
    }

    public void delete(UUID id) {
        Artifact artifact = findById(id);
        artifactRepository.delete(artifact);
    }
}
