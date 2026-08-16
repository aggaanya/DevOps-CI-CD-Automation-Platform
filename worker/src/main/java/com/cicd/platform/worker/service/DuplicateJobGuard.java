package com.cicd.platform.worker.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guard against duplicate execution of the same {@code jobId}.
 *
 * <p>RabbitMQ offers at-least-once delivery: a consumer crash after
 * processing but before ack, or a redelivery, can deliver the same job twice.
 * Within a single worker process this guard ensures the job runs at most
 * once. Entries expire after {@link #TTL_MILLIS} so the guard does not grow
 * unbounded. Cross-process duplicate protection requires a durable store and
 * is a documented Phase 5 improvement.</p>
 */
@Component
public class DuplicateJobGuard {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> completed = new ConcurrentHashMap<>();

    /**
     * Atomically claims a job for execution. Returns {@code true} if this
     * call is the first to claim the {@code jobId} (and the job was not
     * recently completed), {@code false} if it is a duplicate.
     */
    public boolean tryAcquire(String jobId) {
        if (jobId == null) {
            return true;
        }
        evictExpired();
        Long previous = inFlight.putIfAbsent(jobId, System.currentTimeMillis());
        if (previous != null) {
            return false;
        }
        if (completed.containsKey(jobId)) {
            inFlight.remove(jobId);
            return false;
        }
        return true;
    }

    public void markRunning(String jobId) {
        inFlight.put(jobId, System.currentTimeMillis());
    }

    public void markCompleted(String jobId) {
        inFlight.remove(jobId);
        completed.put(jobId, System.currentTimeMillis());
    }

    public void markFailed(String jobId) {
        inFlight.remove(jobId);
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        inFlight.entrySet().removeIf(e -> now - e.getValue() > TTL_MILLIS);
        completed.entrySet().removeIf(e -> now - e.getValue() > TTL_MILLIS);
    }

    public Set<String> runningJobs() {
        return inFlight.keySet();
    }
}
