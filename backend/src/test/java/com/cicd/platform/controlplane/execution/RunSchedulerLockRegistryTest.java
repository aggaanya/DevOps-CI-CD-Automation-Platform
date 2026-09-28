package com.cicd.platform.controlplane.execution;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunSchedulerLockRegistryTest {

    private final RunSchedulerLockRegistry registry = new RunSchedulerLockRegistry();

    @Test
    void withRunLock_returnsActionResult() {
        UUID runId = UUID.randomUUID();

        String result = registry.withRunLock(runId, () -> "done");

        assertEquals("done", result);
    }

    @Test
    void withRunLock_runnableOverload_executesAction() {
        AtomicInteger calls = new AtomicInteger();

        registry.withRunLock(UUID.randomUUID(), calls::incrementAndGet);

        assertEquals(1, calls.get());
    }

    @Test
    void withRunLock_isReentrantForSameThread() {
        UUID runId = UUID.randomUUID();

        String result = registry.withRunLock(runId, () ->
                registry.withRunLock(runId, () -> "inner"));

        assertEquals("inner", result);
    }

    @Test
    void concurrentCallbacksForSameRun_areSerialized() throws Exception {
        UUID runId = UUID.randomUUID();
        int threads = 8;
        int perThread = 500;
        AtomicInteger counter = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        registry.withRunLock(runId, counter::incrementAndGet);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, counter.get());
    }

    @Test
    void withRunLock_returnsNullWhenLockCannotBeAcquired() throws Exception {
        UUID runId = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> registry.withRunLock(runId, () -> {
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));

        Object result = registry.withRunLock(runId, () -> "never", 10);

        assertNull(result);
        release.countDown();
        holder.join(5_000);
    }

    @Test
    void nullRunId_isAllowed_andUsesStripeZero() {
        assertTrue(registry.withRunLock(null, () -> Boolean.TRUE));
    }

    @Test
    void stripeCount_isFixedAtCreation() {
        assertEquals(1024, registry.stripeCount());
    }
}