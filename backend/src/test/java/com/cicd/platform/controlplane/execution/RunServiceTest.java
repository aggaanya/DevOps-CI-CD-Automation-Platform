package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.api.exception.BusinessRuleException;
import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.entity.Repository;
import com.cicd.platform.controlplane.domain.repository.*;
import com.cicd.platform.controlplane.domain.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private PipelineVersionRepository pipelineVersionRepository;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private RepositoryRepository repositoryRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private PipelineJobRepository pipelineJobRepository;
    @Mock private JobAttemptRepository jobAttemptRepository;
    @Mock private PipelineOrchestrator orchestrator;
    @Mock private AuditService auditService;

    private RunService runService;

    @BeforeEach
    void setUp() {
        runService = new RunService(
                pipelineRunRepository, pipelineVersionRepository, pipelineRepository,
                repositoryRepository, pipelineStageRepository, pipelineJobRepository,
                jobAttemptRepository, orchestrator, auditService);
    }

    @Test
    void triggerRun_validInput_createsAndStartsRun() {
        UUID versionId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        Pipeline pipeline = new Pipeline();
        pipeline.setStatus(Pipeline.PipelineStatus.ACTIVE);
        version.setPipeline(pipeline);

        when(pipelineVersionRepository.findById(versionId)).thenReturn(Optional.of(version));
        when(pipelineRepository.findById(any())).thenReturn(Optional.of(pipeline));
        when(pipelineRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.startExecution(any())).thenAnswer(inv -> inv.getArgument(0));

        PipelineRun result = runService.triggerRun(versionId, "sha123", "main", null, "alice");

        assertNotNull(result);
        verify(orchestrator).startExecution(any(PipelineRun.class));
        verify(pipelineRunRepository).save(any());
    }

    @Test
    void triggerRun_versionNotFound_throwsException() {
        UUID versionId = UUID.randomUUID();
        when(pipelineVersionRepository.findById(versionId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> runService.triggerRun(versionId, "sha123", "main", null, "alice"));
    }

    @Test
    void triggerRun_pipelineNotActive_throwsException() {
        UUID versionId = UUID.randomUUID();
        PipelineVersion version = new PipelineVersion();
        Pipeline pipeline = new Pipeline();
        pipeline.setStatus(Pipeline.PipelineStatus.INACTIVE);
        version.setPipeline(pipeline);

        when(pipelineVersionRepository.findById(versionId)).thenReturn(Optional.of(version));
        when(pipelineRepository.findById(pipeline.getId())).thenReturn(Optional.of(pipeline));

        assertThrows(BusinessRuleException.class,
                () -> runService.triggerRun(versionId, "sha123", "main", null, "alice"));
    }

    @Test
    void getRun_found_returnsRun() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        PipelineRun result = runService.getRun(runId);

        assertEquals(run, result);
    }

    @Test
    void getRun_notFound_throwsException() {
        UUID runId = UUID.randomUUID();
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> runService.getRun(runId));
    }

    @Test
    void cancelRun_runningRun_cancelsSuccessfully() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.RUNNING);
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        PipelineRun result = runService.cancelRun(runId);

        verify(orchestrator).cancelRun(runId);
    }

    @Test
    void cancelRun_completedRun_throwsException() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.SUCCESS);
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThrows(BusinessRuleException.class, () -> runService.cancelRun(runId));
    }

    @Test
    void cancelRun_cancelledRun_throwsException() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.CANCELLED);
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThrows(BusinessRuleException.class, () -> runService.cancelRun(runId));
    }

    @Test
    void cancelRun_failedRun_throwsException() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.FAILED);
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThrows(BusinessRuleException.class, () -> runService.cancelRun(runId));
    }

    @Test
    void cancelRun_notFound_throwsException() {
        UUID runId = UUID.randomUUID();
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> runService.cancelRun(runId));
    }

    @Test
    void cancelRun_queuedRun_cancelsSuccessfully() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setStatus(PipelineRun.RunStatus.QUEUED);
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(pipelineRunRepository.findById(runId)).thenReturn(Optional.of(run));

        runService.cancelRun(runId);

        verify(orchestrator).cancelRun(runId);
    }
}
