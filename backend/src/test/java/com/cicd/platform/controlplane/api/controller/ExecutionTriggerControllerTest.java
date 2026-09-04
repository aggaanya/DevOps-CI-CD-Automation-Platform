package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.domain.entity.WorkerResult;
import com.cicd.platform.controlplane.domain.repository.WorkerResultRepository;
import com.cicd.platform.controlplane.execution.JobTriggerService;
import com.cicd.platform.controlplane.execution.JobTriggerService.TriggerRequest;
import com.cicd.platform.controlplane.execution.JobTriggerService.TriggerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionTriggerControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private JobTriggerService jobTriggerService;
    @MockBean private WorkerResultRepository workerResultRepository;

    @Test
    void trigger_validRequest_returnsAcceptedWithIds() throws Exception {
        TriggerResult result = new TriggerResult(
                "job-123", "pipeline-job-123",
                "https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git",
                "deadbeef", "main", "pipeline-remote.yml", "QUEUED");
        when(jobTriggerService.trigger(any(TriggerRequest.class))).thenReturn(result);

        String body = """
                {"repositoryUrl":"https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git","commitSha":"deadbeef","branch":"main","pipelineFile":"pipeline-remote.yml"}
                """;

        mockMvc.perform(post("/api/v1/executions/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.pipelineId").value("pipeline-job-123"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void trigger_repositoryUrlRequired_returnsBadRequest() throws Exception {
        when(jobTriggerService.trigger(any(TriggerRequest.class)))
                .thenThrow(new IllegalArgumentException("repositoryUrl is required"));

        String body = """
                {"repositoryUrl":"","commitSha":"abc"}
                """;

        mockMvc.perform(post("/api/v1/executions/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void latest_allResults_returnedNewestFirst() throws Exception {
        when(workerResultRepository.findTop20ByOrderByReceivedAtDesc())
                .thenReturn(List.of(resultEntity("job-2"), resultEntity("job-1")));

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value("job-2"))
                .andExpect(jsonPath("$[1].jobId").value("job-1"))
                .andExpect(jsonPath("$[1].status").value("SUCCEEDED"));
    }

    @Test
    void latest_filteredByJobId_returnsMatches() throws Exception {
        when(workerResultRepository.findByJobIdOrderByReceivedAtDesc("job-7"))
                .thenReturn(List.of(resultEntity("job-7")));

        mockMvc.perform(get("/api/v1/executions").param("jobId", "job-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value("job-7"));
    }

    @Test
    void byJobId_missing_returnsNotFound() throws Exception {
        when(workerResultRepository.findByJobIdOrderByReceivedAtDesc(anyString()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/executions/job-does-not-exist"))
                .andExpect(status().isNotFound());
    }

    private WorkerResult resultEntity(String jobId) {
        return new WorkerResult(
                jobId, "pipeline-" + jobId, "SUCCEEDED", "worker-test",
                "https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git",
                "deadbeef", "main",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:30Z"),
                30000L, "done", "{}");
    }
}