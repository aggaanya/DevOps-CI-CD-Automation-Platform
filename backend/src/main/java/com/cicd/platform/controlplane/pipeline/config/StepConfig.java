package com.cicd.platform.controlplane.pipeline.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A single command step inside a job.
 *
 * <p>Mirrors the standalone worker module's {@code StepDefinition} so both
 * execution engines accept the same YAML shape. A job that declares no steps
 * falls back to the build-system auto-detection performed by
 * {@code WorkerExecutor}, which is the behaviour every pre-existing pipeline
 * YAML relies on.
 */
public class StepConfig {

    private String name;
    private String run;

    public StepConfig() {}

    public StepConfig(String name, String run) {
        this.name = name;
        this.run = run;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRun() { return run; }
    public void setRun(String run) { this.run = run; }
}
