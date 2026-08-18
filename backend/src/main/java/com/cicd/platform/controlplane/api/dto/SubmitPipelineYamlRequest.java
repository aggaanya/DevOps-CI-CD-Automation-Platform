package com.cicd.platform.controlplane.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitPipelineYamlRequest(
        @NotBlank(message = "YAML content is required")
        String yamlContent
) {}
