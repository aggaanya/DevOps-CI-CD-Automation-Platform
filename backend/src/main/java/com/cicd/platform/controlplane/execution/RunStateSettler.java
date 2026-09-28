package com.cicd.platform.controlplane.execution;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.repository.PipelineJobRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRunRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineStageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settles terminal stage and run states.
 *
 * <p>Extracted from {@code PipelineOrchestrator} because two very different events
 * can be the last thing that happens to a run:
 * <ul>
 *   <li>the final job of the final branch completing, and</li>
 *   <li>the scheduler marking a job SKIPPED because its dependency died — no job
 *       "completed" in that case, but the run still has to reach a terminal
 *       state.</li>
 * </ul>
 * Keeping one implementation means a failed parallel branch and a sequential
 * pipeline converge on identical completion semantics.
 *
 * <p>Completion is a conditional UPDATE, not a read-modify-write, so concurrent
 * callbacks for the same run settle each aggregate exactly once and only the
 * winner emits the corresponding outbox event.
 */
@Component
public class RunStateSettler {

    private static final Logger log = LoggerFactory.getLogger(RunStateSettler.class);

    private static final List<PipelineStage.StageStatus> ACTIVE_STAGE_STATES = List.of(
            PipelineStage.StageStatus.PENDING, PipelineStage.StageStatus.RUNNING);

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final PipelineJobRepository pipelineJobRepository;
    private final StageResultCollector stageResultCollector;
    private final OutboxEventService outboxEventService;

    public RunStateSettler(PipelineRunRepository pipelineRunRepository,
                           PipelineStageRepository pipelineStageRepository,
                           PipelineJobRepository pipelineJobRepository,
                           StageResultCollector stageResultCollector,
                           OutboxEventService outboxEventService) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineStageRepository = pipelineStageRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.stageResultCollector = stageResultCollector;
        this.outboxEventService = outboxEventService;
    }

    /**
     * Settles every stage whose jobs have all finished, then settles the run if
     * that made every stage terminal.
     *
     * <p>Safe to call repeatedly and concurrently: aggregates already terminal are
     * left alone and their events are not re-emitted.
     *
     * @return the run's terminal status when this call completed the run,
     *         otherwise {@code null}
     */
    @Transactional
    public PipelineRun.RunStatus settleRun(UUID runId) {
        if (runId == null) {
            return null;
        }

        List<PipelineStage> stages = pipelineStageRepository
                .findByPipelineRunIdOrderByOrderIndexAsc(runId);
        if (stages.isEmpty()) {
            return null;
        }

        Map<UUID, List<PipelineJob>> jobsByStage = new java.util.LinkedHashMap<>();
        for (PipelineStage stage : stages) {
            List<PipelineJob> jobs = pipelineJobRepository.findByPipelineStageId(stage.getId());
            jobsByStage.put(stage.getId(), jobs);
        }

        List<PipelineStage> settled = new ArrayList<>();
        for (PipelineStage stage : stages) {
            List<PipelineJob> jobs = jobsByStage.get(stage.getId());
            if (!stage.getStatus().isTerminal()
                    && !jobs.isEmpty()
                    && jobs.stream().allMatch(job -> job.getStatus().isTerminal())) {
                PipelineStage.StageStatus stageStatus =
                        stageResultCollector.evaluateStageStatus(stage, jobs);
                Instant now = Instant.now();
                Instant stageStartedAt = jobs.stream()
                        .map(PipelineJob::getStartedAt)
                        .filter(java.util.Objects::nonNull)
                        .min(Instant::compareTo)
                        .orElse(now);
                int updated = pipelineStageRepository.completeStage(
                        stage.getId(), ACTIVE_STAGE_STATES, stageStatus, stageStartedAt, now);
                if (updated == 1) {
                    stage.setStatus(stageStatus);
                    stage.setStartedAt(stage.getStartedAt() == null ? stageStartedAt : stage.getStartedAt());
                    stage.setFinishedAt(now);
                    log.info("[STAGE_COMPLETED] stageId={}, stageName={}, status={}",
                            stage.getId(), stage.getName(), stageStatus);
                    outboxEventService.publishEvent("STAGE_COMPLETED", "PipelineStage",
                            stage.getId(), Map.of(
                                    "stageId", stage.getId(),
                                    "status", stageStatus.name()));
                }
            }
            settled.add(stage);
        }

        if (!settled.stream().allMatch(stage -> stage.getStatus().isTerminal())) {
            return null;
        }

        PipelineRun.RunStatus runStatus = stageResultCollector.evaluateRunStatus(settled);
        int completed = pipelineRunRepository.completeRun(
                runId, PipelineRun.RunStatus.RUNNING, runStatus, Instant.now());
        if (completed == 1) {
            log.info("[RUN_COMPLETED] runId={}, status={}", runId, runStatus);
            outboxEventService.publishEvent("RUN_COMPLETED", "PipelineRun", runId,
                    Map.of("runId", runId, "status", runStatus.name()));
            return runStatus;
        }
        return null;
    }
}
