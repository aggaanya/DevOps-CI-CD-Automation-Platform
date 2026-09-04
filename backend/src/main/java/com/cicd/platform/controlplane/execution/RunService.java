package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.domain.repository.*;
import com.cicd.platform.controlplane.domain.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final PipelineRepository pipelineRepository;
    private final RepositoryRepository repositoryRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final PipelineJobRepository pipelineJobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final PipelineOrchestrator orchestrator;
    private final AuditService auditService;

    public RunService(PipelineRunRepository pipelineRunRepository,
                      PipelineVersionRepository pipelineVersionRepository,
                      PipelineRepository pipelineRepository,
                      RepositoryRepository repositoryRepository,
                      PipelineStageRepository pipelineStageRepository,
                      PipelineJobRepository pipelineJobRepository,
                      JobAttemptRepository jobAttemptRepository,
                      PipelineOrchestrator orchestrator,
                      AuditService auditService) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.pipelineRepository = pipelineRepository;
        this.repositoryRepository = repositoryRepository;
        this.pipelineStageRepository = pipelineStageRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.orchestrator = orchestrator;
        this.auditService = auditService;
    }

    public PipelineRun triggerRun(UUID pipelineVersionId, String commitSha, String branch,
                                  UUID repositoryId, String triggeredBy) {
        if (!ExecutionInputValidator.isValidSafeToken(commitSha)) {
            throw new BusinessRuleException("Commit SHA must be a valid git SHA");
        }
        if (!ExecutionInputValidator.isValidBranch(branch)) {
            throw new BusinessRuleException("Branch contains invalid characters");
        }

        PipelineVersion version = pipelineVersionRepository.findById(pipelineVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion not found with id: " + pipelineVersionId));

        Pipeline pipeline = pipelineRepository.findById(version.getPipeline().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline not found with id: " + version.getPipeline().getId()));

        if (pipeline.getStatus() != Pipeline.PipelineStatus.ACTIVE) {
            throw new BusinessRuleException("Pipeline must be ACTIVE to trigger a run. Current status: " + pipeline.getStatus());
        }

        Repository repository = null;
        if (repositoryId != null) {
            repository = repositoryRepository.findById(repositoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id: " + repositoryId));
        }

        PipelineRun run = new PipelineRun(version, repository, commitSha, branch,
                PipelineRun.TriggerType.API, triggeredBy);
        run.setStatus(PipelineRun.RunStatus.QUEUED);
        run = pipelineRunRepository.save(run);

        log.info("[RUN_TRIGGERED] runId={}, pipelineVersionId={}, branch={}, commitSha={}, triggeredBy={}",
                run.getId(), pipelineVersionId, branch, commitSha, triggeredBy);

        auditService.record(triggeredBy, "TRIGGER_RUN", "PipelineRun", run.getId(),
                java.util.Map.of(
                        "pipelineId", String.valueOf(version.getPipeline().getId()),
                        "branch", String.valueOf(branch),
                        "commitSha", String.valueOf(commitSha)), null);

        run = orchestrator.startExecution(run);

        return run;
    }

    @Transactional(readOnly = true)
    public PipelineRun getRun(UUID runId) {
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineRun not found with id: " + runId));
    }

    @Transactional(readOnly = true)
    public List<PipelineRun> getRunsByVersion(UUID versionId) {
        return pipelineRunRepository.findByPipelineVersionIdOrderByCreatedAtDesc(versionId);
    }

    @Transactional(readOnly = true)
    public List<PipelineRun> getRunsByPipelineId(UUID pipelineId) {
        return pipelineRunRepository.findByPipelineIdOrderByCreatedAtDesc(pipelineId);
    }

    @Transactional(readOnly = true)
    public List<PipelineRun> getRunsByRepositoryId(UUID repositoryId) {
        return pipelineRunRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId);
    }

    public PipelineRun cancelRun(UUID runId) {
        PipelineRun run = pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineRun not found with id: " + runId));

        if (run.getStatus() == PipelineRun.RunStatus.SUCCESS
                || run.getStatus() == PipelineRun.RunStatus.FAILED
                || run.getStatus() == PipelineRun.RunStatus.CANCELLED) {
            throw new BusinessRuleException("Run is already completed with status: " + run.getStatus());
        }

        orchestrator.cancelRun(runId);

        auditService.record(run.getTriggeredBy(), "CANCEL_RUN", "PipelineRun", runId, null, null);

        return pipelineRunRepository.findById(runId).orElse(run);
    }

    @Transactional(readOnly = true)
    public List<PipelineStage> getStages(UUID runId) {
        if (!pipelineRunRepository.existsById(runId)) {
            throw new ResourceNotFoundException("PipelineRun not found with id: " + runId);
        }
        return pipelineStageRepository.findByPipelineRunIdOrderByOrderIndexAsc(runId);
    }

    @Transactional(readOnly = true)
    public List<PipelineJob> getJobs(UUID stageId) {
        if (!pipelineStageRepository.existsById(stageId)) {
            throw new ResourceNotFoundException("PipelineStage not found with id: " + stageId);
        }
        return pipelineJobRepository.findByPipelineStageId(stageId);
    }

    @Transactional(readOnly = true)
    public List<JobAttempt> getAttempts(UUID jobId) {
        if (!pipelineJobRepository.existsById(jobId)) {
            throw new ResourceNotFoundException("PipelineJob not found with id: " + jobId);
        }
        return jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
    }
}
