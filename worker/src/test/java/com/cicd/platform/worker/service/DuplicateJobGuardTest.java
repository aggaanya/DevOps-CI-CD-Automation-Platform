package com.cicd.platform.worker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateJobGuardTest {

    @Test
    void firstAcquireSucceeds() {
        DuplicateJobGuard guard = new DuplicateJobGuard();
        assertTrue(guard.tryAcquire("job-1"));
    }

    @Test
    void duplicateWhileRunningIsRejected() {
        DuplicateJobGuard guard = new DuplicateJobGuard();
        guard.tryAcquire("job-1");
        assertFalse(guard.tryAcquire("job-1"));
    }

    @Test
    void duplicateAfterCompletionIsRejected() {
        DuplicateJobGuard guard = new DuplicateJobGuard();
        guard.tryAcquire("job-1");
        guard.markCompleted("job-1");
        assertFalse(guard.tryAcquire("job-1"));
    }

    @Test
    void failedJobCanBeReacquired() {
        DuplicateJobGuard guard = new DuplicateJobGuard();
        guard.tryAcquire("job-1");
        guard.markFailed("job-1");
        assertTrue(guard.tryAcquire("job-1"));
    }

    @Test
    void differentJobsAreIndependent() {
        DuplicateJobGuard guard = new DuplicateJobGuard();
        guard.tryAcquire("job-1");
        assertTrue(guard.tryAcquire("job-2"));
    }

    @Test
    void concurrentAcquireClaimsOnce() throws Exception {
        DuplicateJobGuard guard = new DuplicateJobGuard();
        int threads = 8;
        Thread[] workers = new Thread[threads];
        java.util.concurrent.atomic.AtomicInteger acquired = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    if (guard.tryAcquire("job-1")) {
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals(1, acquired.get());
    }

    @Test
    void runningJobsAreTracked() {
        DuplicateJobGuard guard = new DuplicateJobGuard();
        guard.tryAcquire("job-1");
        guard.tryAcquire("job-2");
        assertTrue(guard.runningJobs().contains("job-1"));
        assertTrue(guard.runningJobs().contains("job-2"));
    }
}
