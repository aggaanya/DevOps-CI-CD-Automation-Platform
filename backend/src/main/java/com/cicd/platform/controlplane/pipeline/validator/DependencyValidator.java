package com.cicd.platform.controlplane.pipeline.validator;

import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.config.StageConfig;

import java.util.*;

public class DependencyValidator {

    public PipelineValidationResult validate(PipelineConfig config) {
        PipelineValidationResult result = new PipelineValidationResult();
        detectStageCycles(config, result);
        detectJobCycles(config, result);
        return result;
    }

    private void detectStageCycles(PipelineConfig config, PipelineValidationResult result) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (StageConfig stage : config.getStages()) {
            if (stage.getName() != null) {
                String key = stage.getName().toLowerCase();
                graph.putIfAbsent(key, new HashSet<>());
                if (stage.getDependsOn() != null) {
                    for (String dep : stage.getDependsOn()) {
                        if (dep != null) {
                            graph.computeIfAbsent(key, k -> new HashSet<>()).add(dep.toLowerCase());
                        }
                    }
                }
            }
        }

        if (hasCycle(graph)) {
            result.addError("pipeline.stages", "CYCLIC_DEPENDENCY",
                    "Circular dependency detected among pipeline stages");
        }
    }

    private void detectJobCycles(PipelineConfig config, PipelineValidationResult result) {
        for (StageConfig stage : config.getStages()) {
            if (stage.getJobs() == null || stage.getJobs().size() <= 1) continue;

            Map<String, Set<String>> graph = new HashMap<>();
            for (var job : stage.getJobs()) {
                if (job.getName() != null) {
                    String key = job.getName().toLowerCase();
                    graph.putIfAbsent(key, new HashSet<>());
                    if (job.getDependsOn() != null) {
                        for (String dep : job.getDependsOn()) {
                            if (dep != null) {
                                graph.computeIfAbsent(key, k -> new HashSet<>()).add(dep.toLowerCase());
                            }
                        }
                    }
                }
            }

            if (hasCycle(graph)) {
                result.addError("pipeline.stages.jobs", "CYCLIC_DEPENDENCY",
                        "Circular job dependency detected in stage '" + stage.getName() + "'");
            }
        }
    }

    private boolean hasCycle(Map<String, Set<String>> graph) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                if (dfs(node, graph, visiting, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dfs(String node, Map<String, Set<String>> graph,
                        Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (visited.contains(node)) return false;

        visiting.add(node);
        Set<String> neighbors = graph.getOrDefault(node, Collections.emptySet());
        for (String neighbor : neighbors) {
            if (dfs(neighbor, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }
}
