package com.cicd.platform.controlplane.pipeline;

import com.cicd.platform.controlplane.api.exception.ResourceNotFoundException;
import com.cicd.platform.controlplane.domain.entity.Pipeline;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.domain.entity.Project;
import com.cicd.platform.controlplane.domain.repository.PipelineRepository;
import com.cicd.platform.controlplane.domain.repository.PipelineVersionRepository;
import com.cicd.platform.controlplane.domain.repository.ProjectRepository;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.parser.PipelineYamlParser;
import com.cicd.platform.controlplane.pipeline.parser.PipelineYamlParseException;
import com.cicd.platform.controlplane.pipeline.validator.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PipelineYamlService {

    private static final Logger log = LoggerFactory.getLogger(PipelineYamlService.class);

    private final PipelineYamlParser parser;
    private final SchemaValidator schemaValidator;
    private final SemanticValidator semanticValidator;
    private final DependencyValidator dependencyValidator;
    private final PipelineConfigMapper mapper;
    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final ProjectRepository projectRepository;

    public PipelineYamlService(PipelineRepository pipelineRepository,
                               PipelineVersionRepository pipelineVersionRepository,
                               ProjectRepository projectRepository) {
        this.parser = new PipelineYamlParser();
        this.schemaValidator = new SchemaValidator();
        this.semanticValidator = new SemanticValidator();
        this.dependencyValidator = new DependencyValidator();
        this.mapper = new PipelineConfigMapper();
        this.pipelineRepository = pipelineRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.projectRepository = projectRepository;
    }

    public PipelineVersion submitYaml(UUID pipelineId, String yamlContent, String createdBy) {
        log.info("Pipeline YAML submission received for pipeline: {}", pipelineId);

        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline not found with id: " + pipelineId));

        PipelineConfig config = parseYaml(yamlContent);
        List<PipelineValidationFieldError> errors = validate(config);
        if (!errors.isEmpty()) {
            throw new PipelineValidationException("Pipeline configuration is invalid", errors);
        }

        log.info("Pipeline YAML validation passed for pipeline: {}", pipelineId);

        Integer maxVersion = pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipelineId)
                .stream()
                .findFirst()
                .map(PipelineVersion::getVersion)
                .orElse(0);

        PipelineVersion version = new PipelineVersion(pipeline, maxVersion + 1, yamlContent, null, createdBy);
        PipelineVersion saved = pipelineVersionRepository.save(version);

        log.info("Pipeline version {} created for pipeline: {}", saved.getVersion(), pipelineId);
        return saved;
    }

    public PipelineVersion validateAndSubmitToProject(UUID projectId, String yamlContent, String createdBy) {
        log.info("Pipeline YAML submission received for project: {}", projectId);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        PipelineConfig config = parseYaml(yamlContent);
        List<PipelineValidationFieldError> errors = validate(config);
        if (!errors.isEmpty()) {
            throw new PipelineValidationException("Pipeline configuration is invalid", errors);
        }

        Pipeline pipeline = findOrCreatePipeline(project, config);
        Integer maxVersion = pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipeline.getId())
                .stream()
                .findFirst()
                .map(PipelineVersion::getVersion)
                .orElse(0);

        PipelineVersion version = new PipelineVersion(pipeline, maxVersion + 1, yamlContent, null, createdBy);
        PipelineVersion saved = pipelineVersionRepository.save(version);

        log.info("Pipeline version {} created for pipeline '{}' in project {}",
                saved.getVersion(), pipeline.getName(), projectId);
        return saved;
    }

    private PipelineConfig parseYaml(String yamlContent) {
        try {
            return parser.parse(yamlContent);
        } catch (PipelineYamlParseException e) {
            List<PipelineValidationFieldError> errors = new ArrayList<>();
            errors.add(new PipelineValidationFieldError("yaml", "PARSE_ERROR", e.getMessage()));
            throw new PipelineValidationException("Failed to parse pipeline YAML", errors);
        }
    }

    private List<PipelineValidationFieldError> validate(PipelineConfig config) {
        List<PipelineValidationFieldError> allErrors = new ArrayList<>();

        PipelineValidationResult schemaResult = schemaValidator.validate(config);
        allErrors.addAll(schemaResult.getErrors());

        if (schemaResult.isValid()) {
            PipelineValidationResult semanticResult = semanticValidator.validate(config);
            allErrors.addAll(semanticResult.getErrors());

            PipelineValidationResult dependencyResult = dependencyValidator.validate(config);
            allErrors.addAll(dependencyResult.getErrors());
        }

        return allErrors;
    }

    private Pipeline findOrCreatePipeline(Project project, PipelineConfig config) {
        List<Pipeline> existing = pipelineRepository.findByProjectId(project.getId());
        for (Pipeline p : existing) {
            if (p.getName().equalsIgnoreCase(config.getName())) {
                return p;
            }
        }
        Pipeline pipeline = mapper.toPipeline(config, project);
        return pipelineRepository.save(pipeline);
    }
}
