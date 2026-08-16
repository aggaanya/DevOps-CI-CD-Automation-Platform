package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.TestData;
import com.cicd.platform.worker.domain.JobResult;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.StageResult;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.PipelineDefinition;
import com.cicd.platform.worker.pipeline.model.StageDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineExecutorTest {

    private StageExecutor stageExecutor;
    private PipelineExecutor pipelineExecutor;

    @BeforeEach
    void setUp() {
        stageExecutor = mock(StageExecutor.class);
        pipelineExecutor = new PipelineExecutor(stageExecutor);
    }

    private PipelineDefinition pipeline(int stageCount) {
        StepDefinition step = new StepDefinition(StepType.RUN, "a", "echo a", null);
        JobDefinition job = new JobDefinition("j", null, null, Map.of(), List.of(step), List.of());
        return new PipelineDefinition("p", List.of(
                new StageDefinition("s1", List.of(job)),
                new StageDefinition("s2", List.of(job)),
                new StageDefinition("s3", List.of(job))).subList(0, stageCount), "test");
    }

    private StageResult stageResult(JobStatus status) {
        Instant now = Instant.now();
        JobResult job = new JobResult("j", status, now, now, 1L, List.of(), List.of(), "boom");
        return new StageResult("s", status, now, now, 1L, List.of(job), "boom");
    }

    @Test
    void runsAllStagesWhenSuccessful() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        when(stageExecutor.execute(any(), any())).thenReturn(stageResult(JobStatus.SUCCESS));

        PipelineExecutor.ExecutionOutcome outcome = pipelineExecutor.execute(ctx, pipeline(3));

        assertEquals(JobStatus.SUCCESS, outcome.status());
        assertEquals(3, outcome.stages().size());
        verify(stageExecutor, org.mockito.Mockito.times(3)).execute(any(), any());
    }

    @Test
    void stopsOnFailedStageAndCancelsRest() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        when(stageExecutor.execute(any(), any()))
                .thenReturn(stageResult(JobStatus.SUCCESS), stageResult(JobStatus.FAILED));

        PipelineExecutor.ExecutionOutcome outcome = pipelineExecutor.execute(ctx, pipeline(3));

        assertEquals(JobStatus.FAILED, outcome.status());
        assertEquals(3, outcome.stages().size());
        assertEquals(JobStatus.SUCCESS, outcome.stages().get(0).status());
        assertEquals(JobStatus.FAILED, outcome.stages().get(1).status());
        assertEquals(JobStatus.CANCELLED, outcome.stages().get(2).status());
        verify(stageExecutor, org.mockito.Mockito.times(2)).execute(any(), any());
    }

    @Test
    void propagatesTimedOut() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        when(stageExecutor.execute(any(), any())).thenReturn(stageResult(JobStatus.TIMED_OUT));

        PipelineExecutor.ExecutionOutcome outcome = pipelineExecutor.execute(ctx, pipeline(2));

        assertEquals(JobStatus.TIMED_OUT, outcome.status());
        assertEquals(JobStatus.CANCELLED, outcome.stages().get(1).status());
    }

    @Test
    void cancelledContextNeverRunsStages() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        ctx.cancel("watchdog");
        when(stageExecutor.execute(any(), any())).thenReturn(stageResult(JobStatus.SUCCESS));

        PipelineExecutor.ExecutionOutcome outcome = pipelineExecutor.execute(ctx, pipeline(2));

        assertEquals(JobStatus.CANCELLED, outcome.status());
        verify(stageExecutor, never()).execute(any(), any());
    }
}
