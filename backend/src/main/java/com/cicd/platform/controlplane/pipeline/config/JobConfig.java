package com.cicd.platform.controlplane.pipeline.config;

import java.util.ArrayList;
import java.util.List;

public class JobConfig {

    private String name;
    private String type;
    private List<String> dependsOn = new ArrayList<>();

    public JobConfig() {}

    public JobConfig(String name, String type) {
        this.name = name;
        this.type = type;
        this.dependsOn = new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>(); }
}
