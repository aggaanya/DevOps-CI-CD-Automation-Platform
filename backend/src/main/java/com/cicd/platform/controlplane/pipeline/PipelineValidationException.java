package com.cicd.platform.controlplane.pipeline;

import com.cicd.platform.controlplane.pipeline.validator.PipelineValidationFieldError;

import java.util.Collections;
import java.util.List;

public class PipelineValidationException extends RuntimeException {

    private final List<PipelineValidationFieldError> validationErrors;

    public PipelineValidationException(String message, List<PipelineValidationFieldError> validationErrors) {
        super(message);
        this.validationErrors = validationErrors != null
                ? Collections.unmodifiableList(validationErrors)
                : Collections.emptyList();
    }

    public List<PipelineValidationFieldError> getValidationErrors() {
        return validationErrors;
    }
}
