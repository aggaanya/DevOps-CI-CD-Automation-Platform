package com.cicd.platform.worker.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guard against duplicate execution of the same {@code jobId} within the same
 * attempt.
 *
 * <p>RabbitMQ offers at-least-once delivery: a consumer crash after
 * processing but before ack, or a redelivery, can deliver the same job twice.
 * Within a single worker process this guard ensures the job runs at most
 * once per attempt. Entries expire after {@link #TTL_MILLIS} so the guard does
 * not grow unbounded. Cross-process duplicate protection requires a durable
 * store and is a documented Phase 5 improvement.</p>
 *
 * <p>The attempt number is part of the key: a retry reuses the same
 * {@code jobId} but carries a higher {@code attemptNumber}, so it is a new
 * execution, not a duplicate.</p>
 */
@Component
public class DuplicateJobGuard {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> completed = new ConcurrentHashMap<>();

    /**
     * Atomically claims a job for execution. Returns {@code true} if this
     * call is the first to claim the {@code jobId} for the given attempt
     * (and the job was not recently completed for that attempt), {@code false}
     * if it is a duplicate.
     */
    public boolean tryAcquire(String jobId, int attemptNumber) {
        if (jobId == null) {
            return true;
        }
        String key = key(jobId, attemptNumber);
        evictExpired();
        Long previous = inFlight.putIfAbsent(key, System.currentTimeMillis());
        if (previous != null) {
            return false;
        }
        if (completed.containsKey(key)) {
            inFlight.remove(key);
            return false;
        }
        return true;
    }

    public void markRunning(String jobId, int attemptNumber) {
        inFlight.put(key(jobId, attemptNumber), System.currentTimeMillis());
    }

    public void markCompleted(String jobId, int attemptNumber) {
        String key = key(jobId, attemptNumber);
        inFlight.remove(key);
        completed.put(key, System.currentTimeMillis());
    }

    public void markFailed(String jobId, int attemptNumber) {
        inFlight.remove(key(jobId, attemptNumber));
    }

    private static String key(String jobId, int attemptNumber) {
        return jobId + ":" + attemptNumber;
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
