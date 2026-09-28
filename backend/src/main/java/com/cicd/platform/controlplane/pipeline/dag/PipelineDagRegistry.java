package com.cicd.platform.controlplane.pipeline.dag;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;
import com.cicd.platform.controlplane.domain.entity.PipelineVersion;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.JobDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StageDefinition;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StepDefinition;
import com.cicd.platform.controlplane.pipeline.config.PipelineConfig;
import com.cicd.platform.controlplane.pipeline.parser.PipelineYamlParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds and caches the resolved {@link PipelineDag} for a pipeline version.
 *
 * <p>Pipeline versions are immutable, so both the parsed stage definitions and the
 * graph derived from them are immutable and are cached by
 * {@code versionId + yamlHash}. The cache is bounded; on overflow it is cleared
 * rather than grown without limit, which keeps a long-lived control plane from
 * retaining an unbounded number of graphs.
 *
 * <p><b>Resolution order for a run.</b> The DAG persisted on the run's own stage
 * and job rows wins, because it is the exact graph the run was started with.
 * Runs created before the DAG columns existed (for example
 * {@code 5ddd4563-ab23-4eee-bf51-033b37c08f8f}) have no persisted edges, so the
 * graph is re-derived from the immutable version YAML. Both paths are
 * deterministic and produce the same graph for the same declaration.
 *
 * <p>Deliberately a plain object rather than a Spring bean: it is constructed
 * inline by {@code JobDispatcherService}, matching how that class already builds
 * its own parser and mapper, and keeping its constructor signature stable.
 */
public final class PipelineDagRegistry {

    private static final int MAX_CACHE_ENTRIES = 512;

    private final PipelineYamlParser yamlParser = new PipelineYamlParser();
    private final PipelineConfigMapper configMapper = new PipelineConfigMapper();
    private final Map<String, PipelineDag> graphCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<StageDefinition>> cache = new ConcurrentHashMap<>();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    /**
     * Resolves the DAG for a run, preferring persisted edges.
     *
     * @return the graph, or {@code null} when no declaration is available (a run
     *         with neither persisted edges nor parseable YAML); callers then fall
     *         back to positional stage ordering.
     */
    public PipelineDag resolveForRun(PipelineVersion version,
                                     List<PipelineStage> stages,
                                     Map<UUID, List<PipelineJob>> jobsByStage) {
        if (stages != null && !stages.isEmpty()) {
            PipelineDag persisted = fromPersisted(stages, jobsByStage);
            if (persisted != null) {
                return persisted;
            }
        }
        return fromVersion(version);
    }

    /** Builds the DAG from persisted stage/job dependency columns. */
    public PipelineDag fromPersisted(List<PipelineStage> stages, Map<UUID, List<PipelineJob>> jobsByStage) {
        if (stages == null || stages.isEmpty()) {
            return null;
        }
        boolean anyPersisted = stages.stream().anyMatch(PipelineStage::hasDeclaredDependencies);
        if (!anyPersisted) {
            for (List<PipelineJob> jobs : jobsByStage.values()) {
                for (PipelineJob job : jobs) {
                    if (job.hasDeclaredDependencies()) {
                        anyPersisted = true;
                        break;
                    }
                }
            }
            if (!anyPersisted) {
                return null;
            }
        }

        List<StageDefinition> definitions = new ArrayList<>(stages.size());
        for (PipelineStage stage : stages) {
            List<JobDefinition> jobDefinitions = new ArrayList<>();
            for (PipelineJob job : jobsByStage.getOrDefault(stage.getId(), List.of())) {
                jobDefinitions.add(new JobDefinition(
                        job.getName(),
                        job.getJobType(),
                        job.getDependencies()));
            }
            definitions.add(new StageDefinition(
                    stage.getName(),
                    stage.getOrderIndex() == null ? definitions.size() : stage.getOrderIndex(),
                    stage.getDependencies(),
                    jobDefinitions));
        }
        return PipelineDag.from(definitions);
    }

    /**
     * Builds (and caches) the DAG from a version's immutable YAML.
     *
     * <p>The graph itself is cached separately from the stage definitions because it
     * is the expensive part (validation plus topological sort) and it is immutable
     * per {@code versionId + yamlHash}.
     *
     * @return the graph, or {@code null} when the version has no parseable YAML or
     *         the declaration is cyclic/unresolvable
     */
    public PipelineDag fromVersion(PipelineVersion version) {
        if (version == null || version.getYamlContent() == null || version.getYamlContent().isBlank()) {
            return null;
        }
        String key = cacheKey(version);
        PipelineDag cached = graphCache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();
        PipelineDag graph = buildGraph(stageDefinitionsOf(version));
        if (graph == null) {
            return null;
        }
        if (graphCache.size() >= MAX_CACHE_ENTRIES) {
            graphCache.clear();
        }
        graphCache.put(key, graph);
        return graph;
    }

    /**
     * Flattens a version's YAML into stage definitions, including the per-step
     * commands the worker will execute. Cached; safe to call on every dispatch.
     */
    public List<StageDefinition> stageDefinitionsOf(PipelineVersion version) {
        if (version == null || version.getYamlContent() == null || version.getYamlContent().isBlank()) {
            return List.of();
        }
        String key = cacheKey(version);
        List<StageDefinition> cached = cache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();

        PipelineConfig config;
        try {
            config = yamlParser.parse(version.getYamlContent());
        } catch (RuntimeException e) {
            return List.of();
        }
        List<StageDefinition> definitions = configMapper.toStageDefinitions(config);
        if (definitions == null) {
            return List.of();
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, List.copyOf(definitions));
        return definitions;
    }

    /**
     * The declared step commands for one job, in declaration order.
     *
     * <p>Steps are read from the version YAML rather than the persisted DAG because
     * the {@code depends_on} columns intentionally store only the graph, not the
     * command bodies.
     *
     * @return the steps, or an empty list when the job declares none — in which
     *         case the worker falls back to build-system auto-detection
     */
    public List<StepDefinition> stepsFor(PipelineVersion version, String stageName, String jobName) {
        String normalizedStage = normalize(stageName);
        String normalizedJob = normalize(jobName);
        for (StageDefinition stage : stageDefinitionsOf(version)) {
            if (!normalize(stage.name()).equals(normalizedStage)) {
                continue;
            }
            for (JobDefinition job : stage.jobs()) {
                if (normalize(job.name()).equals(normalizedJob)) {
                    return job.steps() == null ? List.of() : job.steps();
                }
            }
            return List.of();
        }
        return List.of();
    }

    /** Stage-name -> declared dependencies, for persisting on run start. */
    public Map<String, List<String>> declaredStageDependencies(List<StageDefinition> definitions) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (StageDefinition definition : definitions) {
            map.put(definition.name(), definition.dependsOn() == null ? List.of() : definition.dependsOn());
        }
        return map;
    }

    private PipelineDag buildGraph(List<StageDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        try {
            return PipelineDag.from(definitions);
        } catch (PipelineDag.CyclicDependencyException | PipelineDag.UnresolvedDependencyException e) {
            // The submit-time validators already reject these; if a graph somehow
            // reaches execution it must not be scheduled. Callers treat null as
            // "no declared graph" and fall back to positional ordering.
            return null;
        }
    }

    private String cacheKey(PipelineVersion version) {
        return version.getId() + ":" + version.getYamlContent().hashCode();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public long cacheHits() {
        return cacheHits.get();
    }

    public long cacheMisses() {
        return cacheMisses.get();
    }

    public int cacheSize() {
        return cache.size() + graphCache.size();
    }
}
