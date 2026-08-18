package com.cicd.platform.controlplane.api.dto;

import com.cicd.platform.controlplane.pipeline.validator.PipelineValidationFieldError;

public record PipelineValidationError(
        String path,
        String code,
        String message
) {
    public static PipelineValidationError from(PipelineValidationFieldError error) {
        return new PipelineValidationError(error.path(), error.code(), error.message());
    }
}
