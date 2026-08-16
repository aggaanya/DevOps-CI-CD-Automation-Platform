package com.cicd.platform.worker.pipeline.model;

import java.util.List;

public record StageDefinition(String name, List<JobDefinition> jobs) {

    public StageDefinition {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
