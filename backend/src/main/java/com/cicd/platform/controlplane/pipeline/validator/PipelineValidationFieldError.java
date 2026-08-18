package com.cicd.platform.controlplane.pipeline.validator;

public record PipelineValidationFieldError(
        String path,
        String code,
        String message
) {}
