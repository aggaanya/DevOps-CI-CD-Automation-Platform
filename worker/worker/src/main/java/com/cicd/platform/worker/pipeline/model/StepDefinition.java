package com.cicd.platform.worker.pipeline.model;

/**
 * A single executable step.
 *
 * @param type    {@link StepType}.
 * @param name    display name (derived from the command when absent).
 * @param command raw command for {@code run} steps; image name for {@code buildImage} steps.
 * @param image   container image used to execute this step (docker sandbox).
 */
public record StepDefinition(StepType type, String name, String command, String image) {
}
