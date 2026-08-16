package com.cicd.platform.worker.pipeline.model;

import java.util.List;
import java.util.Map;

/**
 * A job: an ordered list of steps executed in one workspace.
 *
 * @param name              job name.
 * @param workingDirectory  directory relative to the repository root where steps run.
 * @param image             optional container image for the docker sandbox.
 * @param env               environment variables defined by the (untrusted) pipeline.
 * @param steps             ordered steps.
 * @param artifacts         glob patterns collected after a successful job.
 */
public record JobDefinition(
        String name,
        String workingDirectory,
        String image,
        Map<String, String> env,
        List<StepDefinition> steps,
        List<String> artifacts) {

    public JobDefinition {
        env = env == null ? Map.of() : Map.copyOf(env);
        steps = steps == null ? List.of() : List.copyOf(steps);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
