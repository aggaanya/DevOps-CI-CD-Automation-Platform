package com.cicd.platform.controlplane.pipeline.dag;

import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.JobDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StageDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The resolved, job-level execution graph for a single pipeline version.
 *
 * <p><b>Why job-level.</b> Stages are a presentation/grouping concern; the unit
 * of scheduling is a job. Flattening stage dependencies into job edges lets a
 * single ordering rule answer "may this job run now?", which is what enables
 * independent jobs to become runnable at the same instant.
 *
 * <p><b>Edge construction.</b> For a job {@code J} in stage {@code S}:
 * <ol>
 *   <li>every job explicitly named in {@code J.dependsOn} (same stage); then</li>
 *   <li>every job of every stage named in {@code S.dependsOn}.</li>
 * </ol>
 * If {@code S} declares no {@code dependsOn}, S inherits an implicit edge from
 * <em>every job of every preceding stage</em>. That positional fallback is the
 * behaviour every pre-existing pipeline YAML relies on, and it is what makes
 * "no dependency declared" mean "strictly ordered list".
 *
 * <p>Two independent jobs in the same stage with no {@code dependsOn} therefore
 * have an empty dependency set and are simultaneously eligible — the mechanism
 * behind real parallel execution.
 *
 * <p>Instances are immutable and safe to share across threads; a run builds one
 * and reuses it for every dispatch cycle.
 */
public final class PipelineDag {

    /** Raised when a pipeline declares a dependency cycle or an unresolvable edge. */
    public static class CyclicDependencyException extends RuntimeException {
        public CyclicDependencyException(String message) {
            super(message);
        }
    }

    /** Raised when a declared dependency does not resolve to a known node. */
    public static class UnresolvedDependencyException extends RuntimeException {
        public UnresolvedDependencyException(String message) {
            super(message);
        }
    }

    private record Node(String stageName, String jobName) {
        String key() {
            return stageName + "/" + jobName;
        }
    }

    /**
     * Forward adjacency: node -&gt; the nodes that become runnable once it succeeds.
     * This is the edge direction edges are stored in.
     */
    private final Map<String, List<String>> unblockedBy;
    /**
     * Reverse adjacency: node -&gt; the nodes it must wait for. Built by inverting
     * {@link #unblockedBy}; this is what readiness evaluation reads.
     */
    private final Map<String, List<String>> blockedBy;
    private final List<String> topologicalOrder;
    private final Map<String, String> stageByJobKey;
    private final Map<String, String> jobNameByKey;

    private PipelineDag(Map<String, List<String>> unblockedBy,
                        List<String> topologicalOrder,
                        Map<String, String> stageByJobKey,
                        Map<String, String> jobNameByKey) {
        this.unblockedBy = deepImmutableCopy(unblockedBy);
        this.blockedBy = deepImmutableCopy(invert(this.unblockedBy));
        this.topologicalOrder = List.copyOf(topologicalOrder);
        this.stageByJobKey = Map.copyOf(stageByJobKey);
        this.jobNameByKey = Map.copyOf(jobNameByKey);
    }

    /**
     * {@code Map.copyOf} alone is not enough: the inner lists are the mutable
     * arrays built during graph construction, and the graph is shared across
     * dispatch threads. Each inner list is copied too.
     */
    private static Map<String, List<String>> deepImmutableCopy(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>(source.size() * 2);
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    /**
     * Flattens the stage/job graph described by {@code stageDefinitions} into a
     * job-level DAG.
     *
     * @throws CyclicDependencyException    if the declarations form a cycle
     * @throws UnresolvedDependencyException if a declared name matches no node
     */
    public static PipelineDag from(List<StageDefinition> stageDefinitions) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        Map<String, String> stageByKey = new LinkedHashMap<>();
        Map<String, String> jobNameByKey = new LinkedHashMap<>();
        Map<String, List<String>> jobsByStage = new LinkedHashMap<>();
        Map<String, List<String>> declaredStageDeps = new HashMap<>();
        Map<String, List<String>> declaredJobDeps = new HashMap<>();

        for (StageDefinition stage : stageDefinitions) {
            String stageName = lower(stage.name());
            if (stageName == null) {
                continue;
            }
            List<String> jobKeys = new ArrayList<>();
            for (JobDefinition job : stage.jobs()) {
                String jobName = lower(job.name());
                if (jobName == null) {
                    continue;
                }
                String key = stageName + "/" + jobName;
                if (edges.containsKey(key)) {
                    throw new CyclicDependencyException("Duplicate job '" + key + "' in pipeline");
                }
                jobKeys.add(key);
                edges.put(key, new ArrayList<>());
                stageByKey.put(key, stageName);
                jobNameByKey.put(key, jobName);
                declaredJobDeps.put(key, normalizeAll(job.dependsOn()));
            }
            jobsByStage.put(stageName, jobKeys);
            declaredStageDeps.put(stageName, normalizeAll(stage.dependsOn()));
        }

        // Order of stages as declared, used for the positional fallback.
        List<String> stageOrder = new ArrayList<>(jobsByStage.keySet());

        for (StageDefinition stage : stageDefinitions) {
            String stageName = lower(stage.name());
            if (stageName == null) {
                continue;
            }
            List<String> declaredStageDepNames = declaredStageDeps.getOrDefault(stageName, List.of());

            if (declaredStageDepNames.isEmpty()) {
                // Positional fallback: this stage waits for every job of every
                // preceding stage, so a pipeline that declares no dependencies
                // still executes strictly in written order.
                for (String priorStage : stageOrder) {
                    if (priorStage.equals(stageName)) {
                        break;
                    }
                    for (String priorJobKey : jobsByStage.getOrDefault(priorStage, List.of())) {
                        for (String jobKey : jobsByStage.getOrDefault(stageName, List.of())) {
                            addEdge(edges, priorJobKey, jobKey);
                        }
                    }
                }
            } else {
                for (String depStageName : declaredStageDepNames) {
                    List<String> depJobKeys = jobsByStage.get(depStageName);
                    if (depJobKeys == null) {
                        throw new UnresolvedDependencyException(
                                "Stage '" + stageName + "' depends on unknown stage '" + depStageName + "'");
                    }
                    for (String jobKey : jobsByStage.getOrDefault(stageName, List.of())) {
                        for (String depJobKey : depJobKeys) {
                            addEdge(edges, depJobKey, jobKey);
                        }
                    }
                }
            }

            for (String jobKey : jobsByStage.getOrDefault(stageName, List.of())) {
                for (String depJobName : declaredJobDeps.getOrDefault(jobKey, List.of())) {
                    String depKey = stageName + "/" + depJobName;
                    if (depKey.equals(jobKey)) {
                        throw new CyclicDependencyException(
                                "Job '" + depJobName + "' depends on itself in stage '" + stageName + "'");
                    }
                    if (!edges.containsKey(depKey)) {
                        throw new UnresolvedDependencyException(
                                "Job '" + jobNameByKey.get(jobKey) + "' in stage '" + stageName
                                        + "' depends on unknown job '" + depJobName + "'");
                    }
                    addEdge(edges, depKey, jobKey);
                }
            }
        }

        return new PipelineDag(edges, topologicalSort(edges), stageByKey, jobNameByKey);
    }

    private static void addEdge(Map<String, List<String>> edges, String fromKey, String toKey) {
        if (fromKey.equals(toKey)) {
            return;
        }
        List<String> targets = edges.get(fromKey);
        if (targets == null) {
            throw new UnresolvedDependencyException("Unknown pipeline node '" + fromKey + "'");
        }
        if (!targets.contains(toKey)) {
            targets.add(toKey);
        }
    }

    private static List<String> topologicalSort(Map<String, List<String>> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : edges.keySet()) {
            inDegree.put(node, 0);
        }
        for (List<String> targets : edges.values()) {
            for (String target : targets) {
                inDegree.merge(target, 1, Integer::sum);
            }
        }

        // Deterministic ordering: iterate declared order, not HashMap order.
        Deque<String> ready = new ArrayDeque<>();
        for (String node : edges.keySet()) {
            if (inDegree.get(node) == 0) {
                ready.add(node);
            }
        }

        List<String> order = new ArrayList<>(edges.size());
        while (!ready.isEmpty()) {
            String node = ready.poll();
            order.add(node);
            for (String target : edges.getOrDefault(node, List.of())) {
                int remaining = inDegree.merge(target, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }

        if (order.size() != edges.size()) {
            List<String> cyclic = new ArrayList<>();
            for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
                if (e.getValue() > 0) {
                    cyclic.add(e.getKey());
                }
            }
            throw new CyclicDependencyException(
                    "Circular dependency detected among pipeline jobs: " + String.join(", ", cyclic));
        }
        return order;
    }

    private static Map<String, List<String>> invert(Map<String, List<String>> edges) {
        Map<String, List<String>> inverted = new LinkedHashMap<>();
        for (String node : edges.keySet()) {
            inverted.put(node, new ArrayList<>());
        }
        for (Map.Entry<String, List<String>> e : edges.entrySet()) {
            for (String target : e.getValue()) {
                inverted.computeIfAbsent(target, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        return inverted;
    }

    private static List<String> normalizeAll(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            String normalized = lower(name);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static String lower(String value) {
        return DependencyNames.normalize(value);
    }

    // ------------------------------------------------------------------
    // Query API
    // ------------------------------------------------------------------

    /** Canonical key for a job within a stage: {@code "<stage>/<job>"} (lower-cased). */
    public static String key(String stageName, String jobName) {
        return lower(stageName) + "/" + lower(jobName);
    }

    /**
     * Jobs that must reach a terminal successful state before {@code jobKey}
     * may be dispatched. Empty means the job is eligible immediately, which is
     * how independent jobs become concurrently runnable.
     */
    public List<String> dependenciesOf(String jobKey) {
        return blockedBy.getOrDefault(jobKey, List.of());
    }

    /** Jobs that become eligible once {@code jobKey} succeeds. */
    public List<String> dependentsOf(String jobKey) {
        return unblockedBy.getOrDefault(jobKey, List.of());
    }

    public boolean hasNode(String jobKey) {
        return unblockedBy.containsKey(jobKey);
    }

    /** All job keys in a deterministic topological order. */
    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    public Set<String> jobKeys() {
        return unblockedBy.keySet();
    }

    public String stageOf(String jobKey) {
        return stageByJobKey.get(jobKey);
    }

    public String jobNameOf(String jobKey) {
        return jobNameByKey.get(jobKey);
    }

    /** The stage-level readiness groups: stage name -> ordered job keys. */
    public Map<String, List<String>> jobsByStage() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String key : topologicalOrder) {
            grouped.computeIfAbsent(stageByJobKey.get(key), k -> new ArrayList<>()).add(key);
        }
        return grouped;
    }

    @Override
    public String toString() {
        return "PipelineDag{nodes=" + unblockedBy.size() + ", order=" + topologicalOrder + "}";
    }
}
