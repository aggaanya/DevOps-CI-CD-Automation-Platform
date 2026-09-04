package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.WorkerResultResponse;
import com.cicd.platform.controlplane.domain.entity.WorkerResult;
import com.cicd.platform.controlplane.domain.repository.WorkerResultRepository;
import com.cicd.platform.controlplane.execution.JobTriggerService;
import com.cicd.platform.controlplane.execution.JobTriggerService.TriggerRequest;
import com.cicd.platform.controlplane.execution.JobTriggerService.TriggerResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Submits ad-hoc pipeline executions to standalone workers through the
 * {@code cicd.jobs.exchange} topology. The job is enqueued and acknowledged
 * asynchronously by the worker; results are recorded by the control plane once
 * the worker publishes them and can be inspected through the read endpoints.
 */
@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionTriggerController {

    private final JobTriggerService jobTriggerService;
    private final WorkerResultRepository workerResultRepository;

    public ExecutionTriggerController(JobTriggerService jobTriggerService,
                                      WorkerResultRepository workerResultRepository) {
        this.jobTriggerService = jobTriggerService;
        this.workerResultRepository = workerResultRepository;
    }

    @PostMapping("/trigger")
    public ResponseEntity<TriggerResult> trigger(@RequestBody TriggerRequest request) {
        TriggerResult result = jobTriggerService.trigger(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<WorkerResultResponse>> latest(
            @RequestParam(required = false) String jobId) {
        List<WorkerResult> results = (jobId == null || jobId.isBlank())
                ? workerResultRepository.findTop20ByOrderByReceivedAtDesc()
                : workerResultRepository.findByJobIdOrderByReceivedAtDesc(jobId);
        return ResponseEntity.ok(results.stream().map(WorkerResultResponse::from).toList());
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<WorkerResultResponse> latestForJob(@PathVariable String jobId) {
        return workerResultRepository.findByJobIdOrderByReceivedAtDesc(jobId).stream()
                .findFirst()
                .map(WorkerResultResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}