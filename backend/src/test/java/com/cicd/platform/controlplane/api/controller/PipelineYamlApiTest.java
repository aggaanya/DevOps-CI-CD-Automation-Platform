package com.cicd.platform.controlplane.api.controller;

import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.repository.OrganizationRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PipelineYamlApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;

    private static final String VALID_YAML =
            "pipeline:\n" +
            "  name: test-pipeline\n" +
            "  description: A test pipeline\n" +
            "  stages:\n" +
            "    - name: build\n" +
            "      jobs:\n" +
            "        - name: compile\n" +
            "          type: BUILD\n" +
            "    - name: test\n" +
            "      dependsOn:\n" +
            "        - build\n" +
            "      jobs:\n" +
            "        - name: unit-test\n" +
            "          type: TEST\n";

    private static final String MISSING_TYPE_YAML =
            "pipeline:\n" +
            "  name: no-type-pipeline\n" +
            "  description: Pipeline with missing job type\n" +
            "  stages:\n" +
            "    - name: build\n" +
            "      jobs:\n" +
            "        - name: compile\n";

    private static final String CYCLIC_YAML =
            "pipeline:\n" +
            "  name: cyclic-pipeline\n" +
            "  description: Pipeline with circular dependencies\n" +
            "  stages:\n" +
            "    - name: build\n" +
            "      dependsOn:\n" +
            "        - test\n" +
            "      jobs:\n" +
            "        - name: compile\n" +
            "          type: BUILD\n" +
            "    - name: test\n" +
            "      dependsOn:\n" +
            "        - build\n" +
            "      jobs:\n" +
            "        - name: unit-test\n" +
            "          type: TEST\n";

    private Organization org;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Test Org", "test-org", "Test organization"));
    }

    @Test
    void submitYamlToExistingPipelineShouldReturn201() throws Exception {
        Project project = projectRepository.save(new Project(org, "CI Project", "ci-project", "desc"));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "CI Pipeline", "desc"));

        String json = objectMapper.writeValueAsString(Map.of("yamlContent", VALID_YAML));

        mockMvc.perform(post("/api/v1/pipelines/" + pipeline.getId() + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.pipelineId").value(pipeline.getId().toString()));
    }

    @Test
    void submitYamlToNonexistentPipelineShouldReturn404() throws Exception {
        UUID fakeId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(Map.of("yamlContent", VALID_YAML));

        mockMvc.perform(post("/api/v1/pipelines/" + fakeId + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void submitYamlWithInvalidContentShouldReturn422() throws Exception {
        Project project = projectRepository.save(new Project(org, "Val Project", "val-project", "desc"));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "Val Pipeline", "desc"));

        String json = objectMapper.writeValueAsString(Map.of("yamlContent", "invalid [{"));

        mockMvc.perform(post("/api/v1/pipelines/" + pipeline.getId() + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PIPELINE_VALIDATION_ERROR"));
    }

    @Test
    void submitYamlWithMissingPipelineKeyShouldReturn422() throws Exception {
        Project project = projectRepository.save(new Project(org, "Key Project", "key-project", "desc"));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "Key Pipeline", "desc"));

        String json = objectMapper.writeValueAsString(Map.of("yamlContent", "stages: [build]"));

        mockMvc.perform(post("/api/v1/pipelines/" + pipeline.getId() + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PIPELINE_VALIDATION_ERROR"));
    }

    @Test
    void submitYamlToProjectShouldReturn201() throws Exception {
        Project project = projectRepository.save(new Project(org, "YAML Project", "yaml-project", "desc"));

        String json = objectMapper.writeValueAsString(Map.of("yamlContent", VALID_YAML));

        mockMvc.perform(post("/api/v1/pipelines/yaml")
                        .param("projectId", project.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.pipelineId").exists());
    }

    @Test
    void submitYamlToNonexistentProjectShouldReturn404() throws Exception {
        UUID fakeProjectId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(Map.of("yamlContent", VALID_YAML));

        mockMvc.perform(post("/api/v1/pipelines/yaml")
                        .param("projectId", fakeProjectId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void submitYamlToProjectWithDuplicateNamesShouldCreateNewVersion() throws Exception {
        Project project = projectRepository.save(new Project(org, "Dup Project", "dup-project", "desc"));

        String json = objectMapper.writeValueAsString(Map.of("yamlContent", VALID_YAML));

        String firstBody = mockMvc.perform(post("/api/v1/pipelines/yaml")
                        .param("projectId", project.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();

        String firstPipelineId = objectMapper.readTree(firstBody).get("pipelineId").asText();

        String secondBody = mockMvc.perform(post("/api/v1/pipelines/yaml")
                        .param("projectId", project.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();

        String secondPipelineId = objectMapper.readTree(secondBody).get("pipelineId").asText();
        assertEquals(firstPipelineId, secondPipelineId);

        mockMvc.perform(get("/api/v1/pipelines/" + firstPipelineId + "/versions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void submitYamlWithSchemaErrorsShouldReturn422() throws Exception {
        Project project = projectRepository.save(new Project(org, "Schema Project", "schema-project", "desc"));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "Schema Pipeline", "desc"));

        String json = objectMapper.writeValueAsString(Map.of("yamlContent", MISSING_TYPE_YAML));

        mockMvc.perform(post("/api/v1/pipelines/" + pipeline.getId() + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PIPELINE_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").exists());
    }

    @Test
    void submitYamlWithDependencyCycleShouldReturn422() throws Exception {
        Project project = projectRepository.save(new Project(org, "Cycle Project", "cycle-project", "desc"));
        Pipeline pipeline = pipelineRepository.save(new Pipeline(project, "Cycle Pipeline", "desc"));

        String json = objectMapper.writeValueAsString(Map.of("yamlContent", CYCLIC_YAML));

        mockMvc.perform(post("/api/v1/pipelines/" + pipeline.getId() + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PIPELINE_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").exists());
    }
}
