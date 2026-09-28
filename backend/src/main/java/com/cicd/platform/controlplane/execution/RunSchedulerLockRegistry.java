package com.cicd.platform.controlplane.execution;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serialises scheduler decisions per pipeline run.
 *
 * <p><b>Why this is needed.</b> Once independent jobs of one run can execute at
 * the same time, several consumer threads can finish at the same moment and each
 * try to advance the run. Their callbacks race on the same {@code pipeline_stages}
 * and {@code pipeline_jobs} rows. Two layers keep that safe:
 * <ul>
 *   <li>this lock, which orders the <em>decisions</em> inside one control plane,
 *       and</li>
 *   <li>conditional UPDATEs ({@code claimForDispatch}, {@code completeStage},
 *       {@code completeRun}), which are what actually make the state correct when
 *       more than one control-plane instance is running.</li>
 * </ul>
 * The lock is an optimisation that avoids wasted work and log interleaving; the
 * database is the source of truth.
 *
 * <p><b>Why it cannot deadlock.</b> Locks are reentrant, so a scheduler pass that
 * settles stages and then dispatches does not deadlock against itself. The lock
 * is never held across job execution — that is what keeps parallelism real — and
 * no other lock is ever acquired while holding it.
 *
 * <p><b>Why it cannot grow without bound.</b> Locks are striped onto a fixed
 * number of slots keyed by run id, so a long-lived control plane retains a fixed
 * amount of memory no matter how many runs it has executed.
 */
@Component
public class RunSchedulerLockRegistry {

    private static final int STRIPES = 1024;
    private static final long DEFAULT_WAIT_MILLIS = 30_000;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final ConcurrentHashMap<UUID, Integer> lastSeen = new ConcurrentHashMap<>();

    public RunSchedulerLockRegistry() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock(true);
        }
    }

    /**
     * Runs {@code action} while holding the lock for {@code runId}, waiting up to
     * {@value #DEFAULT_WAIT_MILLIS} ms for a competing scheduler pass to finish.
     *
     * @return the action's result, or {@code null} if the lock could not be
     *         acquired in time. Returning {@code null} rather than blocking
     *         indefinitely is deliberate: a pass that gives up is simply retried by
     *         the next state change, whereas a control-plane thread stuck forever
     *         would eventually starve the listener pool.
     */
    public <T> T withRunLock(UUID runId, Supplier<T> action) {
        return withRunLock(runId, action, DEFAULT_WAIT_MILLIS);
    }

    public <T> T withRunLock(UUID runId, Supplier<T> action, long waitMillis) {
        ReentrantLock lock = lockFor(runId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return null;
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                lock.unlock();
            }
            // Cheap bound on the bookkeeping map; correctness does not depend on it.
            if (lastSeen.size() > STRIPES * 4) {
                lastSeen.clear();
            }
        }
    }

    /** Runs {@code action} under the run lock, ignoring the result. */
    public void withRunLock(UUID runId, Runnable action) {
        withRunLock(runId, () -> {
            action.run();
            return null;
        });
    }

    private ReentrantLock lockFor(UUID runId) {
        if (runId == null) {
            return stripes[0];
        }
        return stripes[Math.floorMod(runId.hashCode(), STRIPES)];
    }

    /** Visible for tests/diagnostics: number of stripes currently allocated. */
    public int stripeCount() {
        return STRIPES;
    }
}
