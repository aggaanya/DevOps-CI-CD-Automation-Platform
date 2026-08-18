package com.cicd.platform.controlplane.pipeline;

import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.repository.PipelineRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineYamlServiceTest {

    @Mock
    private PipelineRepository pipelineRepository;

    @Mock
    private PipelineVersionRepository pipelineVersionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private PipelineYamlService pipelineYamlService;

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

    private Organization org;
    private Project project;
    private Pipeline pipeline;
    private UUID pipelineId;

    @BeforeEach
    void setUp() {
        org = new Organization("Test Org", "test-org", "desc");
        project = new Project(org, "Test Project", "test-project", "desc");
        pipeline = new Pipeline(project, "test-pipeline", "desc");
        pipelineId = UUID.randomUUID();
    }

    @Test
    void submitYamlShouldCreateVersion() {
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipelineId)).thenReturn(List.of());
        when(pipelineVersionRepository.save(any(PipelineVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PipelineVersion result = pipelineYamlService.submitYaml(pipelineId, VALID_YAML, "admin");

        assertEquals(1, result.getVersion());
        assertEquals(pipeline, result.getPipeline());
        verify(pipelineVersionRepository).save(any(PipelineVersion.class));
    }

    @Test
    void submitYamlShouldIncrementVersion() {
        PipelineVersion existing = new PipelineVersion(pipeline, 3, "old-yaml", null, "admin");

        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipelineId))
                .thenReturn(List.of(existing));
        when(pipelineVersionRepository.save(any(PipelineVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PipelineVersion result = pipelineYamlService.submitYaml(pipelineId, VALID_YAML, "admin");

        assertEquals(4, result.getVersion());
        verify(pipelineVersionRepository).save(any(PipelineVersion.class));
    }

    @Test
    void submitYamlShouldThrowWhenPipelineNotFound() {
        when(pipelineRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                pipelineYamlService.submitYaml(UUID.randomUUID(), VALID_YAML, "admin"));
    }

    @Test
    void submitYamlShouldThrowWhenYamlInvalid() {
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));

        assertThrows(PipelineValidationException.class, () ->
                pipelineYamlService.submitYaml(pipelineId, "invalid [{", "admin"));
    }

    @Test
    void validateAndSubmitToProjectShouldCreatePipelineIfNeeded() {
        UUID projectId = UUID.randomUUID();

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(pipelineRepository.findByProjectId(any())).thenReturn(List.of());
        when(pipelineRepository.save(any(Pipeline.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(any())).thenReturn(List.of());
        when(pipelineVersionRepository.save(any(PipelineVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        pipelineYamlService.validateAndSubmitToProject(projectId, VALID_YAML, "admin");

        verify(pipelineRepository).save(any(Pipeline.class));
        verify(pipelineVersionRepository).save(any(PipelineVersion.class));
    }

    @Test
    void validateAndSubmitToProjectShouldReuseExistingPipeline() {
        UUID projectId = UUID.randomUUID();
        Pipeline existingPipeline = new Pipeline(project, "test-pipeline", "desc");

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(pipelineRepository.findByProjectId(any())).thenReturn(List.of(existingPipeline));
        when(pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(any())).thenReturn(List.of());
        when(pipelineVersionRepository.save(any(PipelineVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        pipelineYamlService.validateAndSubmitToProject(projectId, VALID_YAML, "admin");

        verify(pipelineRepository, never()).save(any(Pipeline.class));
        verify(pipelineVersionRepository).save(any(PipelineVersion.class));

        ArgumentCaptor<PipelineVersion> captor = ArgumentCaptor.forClass(PipelineVersion.class);
        verify(pipelineVersionRepository).save(captor.capture());
        assertEquals(existingPipeline, captor.getValue().getPipeline());
    }
}
