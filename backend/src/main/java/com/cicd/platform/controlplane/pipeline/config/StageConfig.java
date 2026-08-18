package com.cicd.platform.controlplane.pipeline.config;

import java.util.ArrayList;
import java.util.List;

public class StageConfig {

    private String name;
    private List<JobConfig> jobs = new ArrayList<>();
    private List<String> dependsOn = new ArrayList<>();

    public StageConfig() {}

    public StageConfig(String name) {
        this.name = name;
        this.jobs = new ArrayList<>();
        this.dependsOn = new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<JobConfig> getJobs() { return jobs; }
    public void setJobs(List<JobConfig> jobs) { this.jobs = jobs != null ? jobs : new ArrayList<>(); }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>(); }
}
