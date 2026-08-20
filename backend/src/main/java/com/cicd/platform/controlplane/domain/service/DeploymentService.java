package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Deployment;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.repository.DeploymentRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final PipelineRunRepository pipelineRunRepository;

    public DeploymentService(DeploymentRepository deploymentRepository,
                             PipelineRunRepository pipelineRunRepository) {
        this.deploymentRepository = deploymentRepository;
        this.pipelineRunRepository = pipelineRunRepository;
    }

    public Deployment create(UUID pipelineRunId, String environment) {
        PipelineRun run = pipelineRunRepository.findById(pipelineRunId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineRun not found with id: " + pipelineRunId));
        Deployment deployment = new Deployment(run, environment);
        return deploymentRepository.save(deployment);
    }

    @Transactional(readOnly = true)
    public Deployment findById(UUID id) {
        return deploymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Deployment> findByRunId(UUID runId) {
        return deploymentRepository.findByPipelineRunId(runId);
    }

    @Transactional(readOnly = true)
    public List<Deployment> findByEnvironment(String environment) {
        return deploymentRepository.findByEnvironmentOrderByCreatedAtDesc(environment);
    }

    public Deployment startDeployment(UUID deploymentId) {
        Deployment deployment = findById(deploymentId);
        if (deployment.getStatus() != Deployment.DeploymentStatus.PENDING) {
            throw new BusinessRuleException("Can only start a PENDING deployment. Current status: " + deployment.getStatus());
        }
        deployment.setStatus(Deployment.DeploymentStatus.DEPLOYING);
        deployment.setStartedAt(Instant.now());
        return deploymentRepository.save(deployment);
    }

    public Deployment completeDeployment(UUID deploymentId, boolean success, String endpoint) {
        Deployment deployment = findById(deploymentId);
        deployment.setFinishedAt(Instant.now());
        deployment.setEndpoint(endpoint);
        deployment.setStatus(success ? Deployment.DeploymentStatus.SUCCESS : Deployment.DeploymentStatus.FAILED);
        return deploymentRepository.save(deployment);
    }

    public void delete(UUID id) {
        Deployment deployment = findById(id);
        deploymentRepository.delete(deployment);
    }
}
