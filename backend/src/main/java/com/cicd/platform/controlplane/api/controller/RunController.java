package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.*;
import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineRun;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.JobAttempt;
import com.cicd.platform.controlplane.execution.RunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RunResponse> triggerRun(@Valid @RequestBody TriggerPipelineRunRequest request) {
        PipelineRun run = runService.triggerRun(
                request.pipelineVersionId(),
                request.commitSha(),
                request.branch(),
                request.repositoryId(),
                request.triggeredBy());
        return ResponseEntity.status(HttpStatus.CREATED).body(RunResponse.from(run));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> getRun(@PathVariable UUID id) {
        PipelineRun run = runService.getRun(id);
        return ResponseEntity.ok(RunResponse.from(run));
    }

    @GetMapping
    public ResponseEntity<List<RunResponse>> listRuns(@RequestParam UUID versionId) {
        List<RunResponse> runs = runService.getRunsByVersion(versionId).stream()
                .map(RunResponse::from)
                .toList();
        return ResponseEntity.ok(runs);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<RunResponse> cancelRun(@PathVariable UUID id) {
        PipelineRun run = runService.cancelRun(id);
        return ResponseEntity.ok(RunResponse.from(run));
    }

    @GetMapping("/{id}/stages")
    public ResponseEntity<List<StageResponse>> getStages(@PathVariable UUID id) {
        List<StageResponse> stages = runService.getStages(id).stream()
                .map(StageResponse::from)
                .toList();
        return ResponseEntity.ok(stages);
    }

    @GetMapping("/{runId}/stages/{stageId}/jobs")
    public ResponseEntity<List<JobResponse>> getJobs(
            @PathVariable UUID runId, @PathVariable UUID stageId) {
        runService.getRun(runId);
        List<JobResponse> jobs = runService.getJobs(stageId).stream()
                .map(JobResponse::from)
                .toList();
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/{runId}/stages/{stageId}/jobs/{jobId}/attempts")
    public ResponseEntity<List<AttemptResponse>> getAttempts(
            @PathVariable UUID runId, @PathVariable UUID stageId,
            @PathVariable UUID jobId) {
        runService.getRun(runId);
        List<AttemptResponse> attempts = runService.getAttempts(jobId).stream()
                .map(AttemptResponse::from)
                .toList();
        return ResponseEntity.ok(attempts);
    }
}
