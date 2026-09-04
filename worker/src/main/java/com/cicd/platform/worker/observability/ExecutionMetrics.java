package com.cicd.platform.worker.observability;

import com.cicd.platform.worker.domain.JobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Job execution metrics exposed through Micrometer (actuator + Prometheus).
 */
@Component
public class ExecutionMetrics {

    private final AtomicLong runningJobs = new AtomicLong();
    private final Counter jobsStarted;
    private final Counter jobsMalformed;
    private final Counter jobsValidationFailure;
    private final Counter jobsInfrastructureFailure;
    private final MeterRegistry registry;
    private final ConcurrentHashMap<JobStatus, Counter> completedByStatus = new ConcurrentHashMap<>();

    public ExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.jobsStarted = Counter.builder("cicd.jobs.started").description("Pipeline jobs started").register(registry);
        this.jobsMalformed = Counter.builder("cicd.jobs.malformed").description("Malformed job messages").register(registry);
        this.jobsValidationFailure = Counter.builder("cicd.jobs.validation_failed")
                .description("Jobs rejected by validation").register(registry);
        this.jobsInfrastructureFailure = Counter.builder("cicd.jobs.infrastructure_failed")
                .description("Jobs failed due to infrastructure problems").register(registry);
        registry.gauge("cicd.jobs.running", runningJobs, AtomicLong::get);
    }

    public void jobStarted() {
        runningJobs.incrementAndGet();
        jobsStarted.increment();
    }

    public void jobFinished(JobStatus status) {
        runningJobs.decrementAndGet();
        completedByStatus.computeIfAbsent(status, s -> Counter.builder("cicd.jobs.completed")
                        .description("Pipeline jobs completed")
                        .tag("status", s.name())
                        .register(registry))
                .increment();
    }

    public void countMalformed() {
        jobsMalformed.increment();
    }

    public void countValidationFailure() {
        jobsValidationFailure.increment();
    }

    public void jobInfrastructureFailure() {
        jobsInfrastructureFailure.increment();
        runningJobs.decrementAndGet();
    }

    public long running() {
        return runningJobs.get();
    }
}
