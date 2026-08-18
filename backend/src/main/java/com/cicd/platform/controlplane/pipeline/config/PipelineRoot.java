package com.cicd.platform.controlplane.pipeline.config;

public class PipelineRoot {

    private PipelineConfig pipeline;

    public PipelineRoot() {}

    public PipelineRoot(PipelineConfig pipeline) {
        this.pipeline = pipeline;
    }

    public PipelineConfig getPipeline() { return pipeline; }
    public void setPipeline(PipelineConfig pipeline) { this.pipeline = pipeline; }
}
