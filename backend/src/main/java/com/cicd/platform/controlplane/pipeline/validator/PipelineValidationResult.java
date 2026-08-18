package com.cicd.platform.controlplane.pipeline.validator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PipelineValidationResult {

    private final List<PipelineValidationFieldError> errors;

    public PipelineValidationResult() {
        this.errors = new ArrayList<>();
    }

    public PipelineValidationResult(List<PipelineValidationFieldError> errors) {
        this.errors = new ArrayList<>(errors);
    }

    public void addError(String path, String code, String message) {
        errors.add(new PipelineValidationFieldError(path, code, message));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public List<PipelineValidationFieldError> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
