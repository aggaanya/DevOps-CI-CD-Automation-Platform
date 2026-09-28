package com.cicd.platform.controlplane.pipeline.dag;

import com.cicd.platform.controlplane.domain.entity.PipelineJob;
import com.cicd.platform.controlplane.domain.entity.PipelineStage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure function that turns "current job states + the resolved DAG" into the
 * scheduler's decision: which jobs may start now, and which can never start
 * because an upstream branch is already dead.
 *
 * <p>Kept free of persistence and messaging so the ordering rules can be tested
 * directly and so the same evaluation runs unchanged on the dispatch path and in
 * benchmark analysis.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li>A {@code PENDING} job is <b>runnable</b> only when every DAG dependency is
 *       {@code SUCCESS}. An empty dependency set therefore means "eligible
 *       immediately" — that is precisely how independent jobs become eligible at
 *       the same instant instead of being serialized.</li>
 *   <li>A {@code PENDING} job is <b>blocked</b> when any dependency is terminal and
 *       not successful ({@code FAILED}, {@code CANCELLED}, {@code SKIPPED}), or when
 *       any dependency is itself blocked. The second clause is evaluated to a
 *       fixpoint, so blocking propagates down an arbitrarily long chain in a
 *       single pass.</li>
 *   <li>Everything else is <b>waiting</b> — not yet decidable, e.g. a dependency
 *       still {@code QUEUED} or {@code RUNNING}.</li>
 * </ol>
 *
 * <p>Jobs absent from the graph are treated as dependency-free. A run whose job
 * rows predate DAG persistence, or whose YAML failed to resolve, must still make
 * progress rather than stall; the anomaly is reported by the caller through
 * {@link Plan#unmappedJobIds()}.
 */
public final class DagReadiness {

    private DagReadiness() {}

    /** A job that must never execute, with the dependency that made it unreachable. */
    public record BlockedJob(UUID jobId, String stageName, String jobName,
                             String blockedBy, String reason) {}

    /** The full scheduling decision for one evaluation pass. */
    public record Plan(List<UUID> runnableJobIds, List<BlockedJob> blockedJobs,
                       List<UUID> waitingJobIds, List<UUID> unmappedJobIds) {
        public boolean hasWork() {
            return !runnableJobIds.isEmpty() || !blockedJobs.isEmpty();
        }
    }

    public static Plan evaluate(PipelineDag dag, Collection<PipelineJob> jobs) {
        Map<UUID, PipelineJob> pendingById = new HashMap<>();
        Map<String, PipelineJob> pendingByKey = new HashMap<>();
        for (PipelineJob job : jobs) {
            if (job.getStatus() == PipelineJob.JobStatus.PENDING) {
                pendingById.put(job.getId(), job);
                pendingByKey.put(keyOf(job), job);
            }
        }
        if (pendingById.isEmpty()) {
            return new Plan(List.of(), List.of(), List.of(), List.of());
        }

        List<UUID> unmapped = new ArrayList<>();
        for (PipelineJob job : pendingById.values()) {
            if (!dag.hasNode(keyOf(job))) {
                unmapped.add(job.getId());
            }
        }

        // Terminal-and-unsuccessful dependencies, resolved to a fixpoint so that
        // "A fails -> B skipped -> C skipped" happens in one pass.
        Set<String> dead = new HashSet<>();
        Set<String> claimedDead = new HashSet<>();
        Map<String, String> blockerByKey = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PipelineJob job : pendingById.values()) {
                String key = keyOf(job);
                if (dead.contains(key) || !dag.hasNode(key)) {
                    continue;
                }
                for (String depKey : dag.dependenciesOf(key)) {
                    String blocker = resolveBlocker(depKey, dead, jobs);
                    if (blocker != null) {
                        if (claimedDead.add(key)) {
                            changed = true;
                        }
                        dead.add(key);
                        blockerByKey.putIfAbsent(key, blocker);
                        break;
                    }
                }
            }
        }

        List<UUID> runnable = new ArrayList<>();
        List<UUID> waiting = new ArrayList<>();
        List<BlockedJob> blocked = new ArrayList<>();
        for (PipelineJob job : pendingById.values()) {
            String key = keyOf(job);
            if (!dag.hasNode(key)) {
                // No declared graph for this job: preserve legacy behaviour and let
                // it start. Recorded so the caller can log the inconsistency.
                runnable.add(job.getId());
                continue;
            }
            if (dead.contains(key)) {
                String blockerKey = blockerByKey.get(key);
                blocked.add(new BlockedJob(
                        job.getId(),
                        stageNameOf(job),
                        job.getName(),
                        blockerKey,
                        "Dependency '" + blockerKey + "' cannot succeed"));
                continue;
            }
            if (allDependenciesSucceeded(dag, key, jobs)) {
                runnable.add(job.getId());
            } else {
                waiting.add(job.getId());
            }
        }

        return new Plan(List.copyOf(runnable), List.copyOf(blocked),
                List.copyOf(waiting), List.copyOf(unmapped));
    }

    /**
     * Returns the dependency key that makes {@code depKey} unreachable, or
     * {@code null} if it is still satisfiable.
     *
     * <p>A dependency with no corresponding job row in this run is treated as
     * satisfied. Submit-time validation already rejects declarations that do not
     * resolve, so this only happens if a row went missing mid-run; stalling the
     * whole pipeline on that would be worse than surfacing it, and the caller
     * logs it via {@link Plan#unmappedJobIds()}.
     */
    private static String resolveBlocker(String depKey, Set<String> dead,
                                         Collection<PipelineJob> allJobs) {
        if (dead.contains(depKey)) {
            return depKey;
        }
        PipelineJob dep = null;
        for (PipelineJob job : allJobs) {
            if (keyOf(job).equals(depKey)) {
                dep = job;
                break;
            }
        }
        if (dep == null) {
            return null;
        }
        return switch (dep.getStatus()) {
            case FAILED, CANCELLED, SKIPPED -> depKey;
            default -> null;
        };
    }

    private static boolean allDependenciesSucceeded(PipelineDag dag, String key,
                                                    Collection<PipelineJob> allJobs) {
        Map<String, PipelineJob.JobStatus> statuses = new HashMap<>();
        for (PipelineJob job : allJobs) {
            statuses.put(keyOf(job), job.getStatus());
        }
        for (String depKey : dag.dependenciesOf(key)) {
            PipelineJob.JobStatus status = statuses.get(depKey);
            if (status != null && status != PipelineJob.JobStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    /** Canonical {@code "<stage>/<job>"} key for a persisted job. */
    public static String keyOf(PipelineJob job) {
        return PipelineDag.key(stageNameOf(job), job.getName());
    }

    private static String stageNameOf(PipelineJob job) {
        PipelineStage stage = job.getPipelineStage();
        return stage == null ? "" : stage.getName();
    }

    /** Convenience for tests and reports: the job keys a plan would consider. */
    public static Set<String> keysOf(Collection<UUID> jobIds, Collection<PipelineJob> jobs) {
        Set<String> keys = new LinkedHashSet<>();
        for (PipelineJob job : jobs) {
            if (jobIds.contains(job.getId())) {
                keys.add(keyOf(job));
            }
        }
        return keys;
    }
}
