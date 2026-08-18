package com.cicd.platform.controlplane.pipeline.config;

import java.util.ArrayList;
import java.util.List;

public class PipelineConfig {

    private String name;
    private String description;
    private List<StageConfig> stages = new ArrayList<>();

    public PipelineConfig() {}

    public PipelineConfig(String name, String description, List<StageConfig> stages) {
        this.name = name;
        this.description = description;
        this.stages = stages != null ? stages : new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<StageConfig> getStages() { return stages; }
    public void setStages(List<StageConfig> stages) { this.stages = stages != null ? stages : new ArrayList<>(); }
}
