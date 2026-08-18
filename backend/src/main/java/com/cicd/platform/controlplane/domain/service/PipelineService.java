package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.repository.PipelineRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final ProjectRepository projectRepository;

    public PipelineService(PipelineRepository pipelineRepository,
                           PipelineVersionRepository pipelineVersionRepository,
                           ProjectRepository projectRepository) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.projectRepository = projectRepository;
    }

    public Pipeline create(UUID projectId, String name, String description) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        Pipeline pipeline = new Pipeline(project, name, description);
        return pipelineRepository.save(pipeline);
    }

    @Transactional(readOnly = true)
    public Pipeline findById(UUID id) {
        return pipelineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Pipeline> findByProjectId(UUID projectId) {
        return pipelineRepository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public List<PipelineVersion> findVersions(UUID pipelineId) {
        if (!pipelineRepository.existsById(pipelineId)) {
            throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        }
        return pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipelineId);
    }

    public PipelineVersion addVersion(UUID pipelineId, String yamlContent, String commitSha, String createdBy) {
        Pipeline pipeline = findById(pipelineId);

        Integer maxVersion = pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipelineId)
                .stream()
                .findFirst()
                .map(PipelineVersion::getVersion)
                .orElse(0);

        PipelineVersion version = new PipelineVersion(pipeline, maxVersion + 1, yamlContent, commitSha, createdBy);
        return pipelineVersionRepository.save(version);
    }
}
