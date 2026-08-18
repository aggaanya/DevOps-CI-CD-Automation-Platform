package com.cicd.platform.controlplane.health;

import java.time.Instant;
import java.util.Map;

public record HealthResponse(
        String status,
        String service,
        Map<String, String> components,
        Instant timestamp
) {
    public static HealthResponse up(Map<String, String> components) {
        return new HealthResponse("UP", "cicd-control-plane", components, Instant.now());
    }

    public static HealthResponse degraded(Map<String, String> components) {
        return new HealthResponse("DEGRADED", "cicd-control-plane", components, Instant.now());
    }
}
