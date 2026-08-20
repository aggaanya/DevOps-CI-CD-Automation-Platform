package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.api.dto.TriggerPipelineRunRequest;
import com.cicd.platform.controlplane.api.exception.GlobalExceptionHandler;
import com.cicd.platform.controlplane.domain.entity.*;
import com.cicd.platform.controlplane.execution.RunService;
import com.cicd.platform.controlplane.execution.OutboxEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GlobalExceptionHandler.class)
class RunControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RunService runService;

    @MockBean
    private OutboxEventService outboxEventService;

    @Autowired
    private ObjectMapper objectMapper;

    private PipelineRun buildDummyRun() {
        Organization org = new Organization("org", "org-slug", "An org");
        Project project = new Project(org, "proj", "proj-slug", "desc");
        Pipeline pipeline = new Pipeline(project, "pipe", "desc");
        PipelineVersion version = new PipelineVersion(pipeline, 1, "yaml", "sha123", "alice");
        PipelineRun run = new PipelineRun(version, null, "sha123", "main",
                PipelineRun.TriggerType.API, "alice");
        run.setStatus(PipelineRun.RunStatus.QUEUED);
        return run;
    }

    @Test
    void triggerRun_returnsCreated() throws Exception {
        PipelineRun run = buildDummyRun();
        when(runService.triggerRun(any(UUID.class), anyString(), anyString(), any(), anyString()))
                .thenReturn(run);

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TriggerPipelineRunRequest(
                                        UUID.randomUUID(), "sha123", "main", null, "alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commitSha").value("sha123"))
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.triggerType").value("API"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void getRun_returnsOk() throws Exception {
        PipelineRun run = buildDummyRun();
        UUID runId = UUID.randomUUID();
        when(runService.getRun(runId)).thenReturn(run);

        mockMvc.perform(get("/api/v1/runs/" + runId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").value("sha123"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }
}
