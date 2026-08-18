package com.cicd.platform.controlplane.domain.service;

import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Organization;
import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.repository.PipelineRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    @Mock
    private PipelineRepository pipelineRepository;

    @Mock
    private PipelineVersionRepository pipelineVersionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private PipelineService pipelineService;

    private Project project;
    private Pipeline pipeline;

    @BeforeEach
    void setUp() {
        Organization org = new Organization("Test Org", "test-org", "desc");
        project = new Project(org, "Test Project", "test-project", "desc");
        pipeline = new Pipeline(project, "Build Pipeline", "Build and test");
    }

    @Test
    void createShouldPersistPipeline() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(project));
        when(pipelineRepository.save(any(Pipeline.class))).thenReturn(pipeline);

        Pipeline result = pipelineService.create(UUID.randomUUID(), "Build Pipeline", "Build and test");

        assertNotNull(result);
        assertEquals("Build Pipeline", result.getName());
        verify(pipelineRepository).save(any(Pipeline.class));
    }

    @Test
    void createShouldThrowWhenProjectNotFound() {
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                pipelineService.create(UUID.randomUUID(), "name", "desc"));
    }

    @Test
    void findByIdShouldReturnExisting() {
        when(pipelineRepository.findById(any(UUID.class))).thenReturn(Optional.of(pipeline));

        Pipeline result = pipelineService.findById(UUID.randomUUID());

        assertNotNull(result);
        assertEquals("Build Pipeline", result.getName());
    }

    @Test
    void findByIdShouldThrowWhenNotFound() {
        when(pipelineRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                pipelineService.findById(UUID.randomUUID()));
    }
}
