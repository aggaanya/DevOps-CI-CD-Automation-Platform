package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.TestData;
import com.cicd.platform.worker.domain.JobResult;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.StepResult;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobExecutorTest {

    private StepExecutor stepExecutor;
    private ArtifactCollector artifactCollector;
    private JobExecutor jobExecutor;

    @BeforeEach
    void setUp() {
        stepExecutor = mock(StepExecutor.class);
        artifactCollector = mock(ArtifactCollector.class);
        jobExecutor = new JobExecutor(stepExecutor, artifactCollector);
    }

    private StepResult stepResult(JobStatus status, String name) {
        Instant now = Instant.now();
        return new StepResult(name, StepType.RUN, "echo " + name, status, status == JobStatus.SUCCESS ? 0 : 1,
                now, now, 1L, "", "", status == JobStatus.SUCCESS ? null : "boom");
    }

    @Test
    void runsAllStepsOnSuccess() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(
                new StepDefinition(StepType.RUN, "a", "echo a", null),
                new StepDefinition(StepType.RUN, "b", "echo b", null)), List.of());

        when(stepExecutor.execute(any(), any(), any(), anyInt()))
                .thenReturn(stepResult(JobStatus.SUCCESS, "a"), stepResult(JobStatus.SUCCESS, "b"));

        JobResult result = jobExecutor.execute(ctx, jobDef);

        assertEquals(JobStatus.SUCCESS, result.status());
        assertEquals(2, result.steps().size());
        verify(stepExecutor, org.mockito.Mockito.times(2)).execute(any(), any(), any(), anyInt());
    }

    @Test
    void stopsOnFirstFailure() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(
                new StepDefinition(StepType.RUN, "ok", "echo ok", null),
                new StepDefinition(StepType.RUN, "bad", "exit 1", null),
                new StepDefinition(StepType.RUN, "never", "echo never", null)), List.of());

        when(stepExecutor.execute(any(), any(), any(), anyInt()))
                .thenReturn(stepResult(JobStatus.SUCCESS, "ok"), stepResult(JobStatus.FAILED, "bad"));

        JobResult result = jobExecutor.execute(ctx, jobDef);

        assertEquals(JobStatus.FAILED, result.status());
        assertEquals(2, result.steps().size(), "steps after the failure must not run");
        assertTrue(result.error().contains("boom"));
    }

    @Test
    void propagatesTimedOutStatus() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(
                new StepDefinition(StepType.RUN, "slow", "sleep 999", null)), List.of());

        when(stepExecutor.execute(any(), any(), any(), anyInt()))
                .thenReturn(stepResult(JobStatus.TIMED_OUT, "slow"));

        JobResult result = jobExecutor.execute(ctx, jobDef);

        assertEquals(JobStatus.TIMED_OUT, result.status());
    }

    @Test
    void cancelledJobStops() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        ctx.cancel("watchdog timeout");
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(
                new StepDefinition(StepType.RUN, "a", "echo a", null)), List.of());

        JobResult result = jobExecutor.execute(ctx, jobDef);

        assertEquals(JobStatus.CANCELLED, result.status());
    }
}
