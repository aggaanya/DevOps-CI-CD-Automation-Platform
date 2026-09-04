package com.cicd.platform.worker.execution;

import com.cicd.platform.worker.command.CommandExecutor;
import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.CommandResult;
import com.cicd.platform.worker.domain.JobStatus;
import com.cicd.platform.worker.domain.StepResult;
import com.cicd.platform.worker.exception.CommandExecutionException;
import com.cicd.platform.worker.exception.CommandTimeoutException;
import com.cicd.platform.worker.exception.PipelineExecutionException;
import com.cicd.platform.worker.logging.MdcContext;
import com.cicd.platform.worker.pipeline.model.JobDefinition;
import com.cicd.platform.worker.pipeline.model.StepDefinition;
import com.cicd.platform.worker.pipeline.model.StepType;
import com.cicd.platform.worker.security.CommandSecurityPolicy;
import com.cicd.platform.worker.security.SecurityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes a single step inside the job workspace. Validates the command and
 * environment, computes the sandbox request and converts the outcome into a
 * {@link StepResult}. A non-zero exit code is a normal FAILED result; a
 * timeout maps to TIMED_OUT.
 */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final CommandExecutor commandExecutor;
    private final CommandSecurityPolicy securityPolicy;
    private final WorkerProperties props;
    private final SandboxEnv sandboxEnv;

    public StepExecutor(CommandExecutor commandExecutor, CommandSecurityPolicy securityPolicy,
                        WorkerProperties props, SandboxEnv sandboxEnv) {
        this.commandExecutor = commandExecutor;
        this.securityPolicy = securityPolicy;
        this.props = props;
        this.sandboxEnv = sandboxEnv;
    }

    public StepResult execute(ExecutionContext ctx, JobDefinition job, StepDefinition step, int index) {
        MdcContext.putStageJobStep(ctx.job().branch(), job.name(), step.name());
        log.info("Starting step [{}] ({}): {}", step.name(), step.type(), step.command());
        ctx.logs().log("==> STEP " + (index + 1) + ": " + step.name()
                + " (" + step.type() + ") " + step.command());

        Instant startedAt = Instant.now();
        try {
            if (ctx.isCancelled()) {
                return stepResult(step, JobStatus.CANCELLED, -1, "", "", startedAt, Instant.now(),
                        "Pipeline cancelled: " + ctx.cancellationReason());
            }

            Map<String, String> effectiveEnv = effectiveEnvironment(ctx, job);
            Path workdir = resolveWorkDir(ctx, job);
            String workdirRelative = job.workingDirectory() == null ? "." : job.workingDirectory();

            switch (step.type()) {
                case RUN -> {
                    securityPolicy.validateCommand(step.command());
                    securityPolicy.validateEnvironment(job.env());
                    CommandResult result = commandExecutor.execute(step.command(),
                            ctx.workspace().root(), workdir, workdirRelative, effectiveEnv,
                            props.getCommandTimeoutMs(), ctx.job().jobId());
                    recordOutput(ctx, result);
                    return stepResult(step, mapStatus(result), result.exitCode(), result.stdout(),
                            result.stderr(), startedAt, result.completedAt(),
                            result.status() == com.cicd.platform.worker.domain.CommandStatus.FAILED
                                    ? "Command exited with code " + result.exitCode() : null);
                }
                case BUILD_IMAGE -> {
                    if (!props.isBuildImageEnabled()) {
                        throw new SecurityViolationException(
                                "buildImage steps are disabled on this worker (worker.build-image-enabled=false)");
                    }
                    return executeBuildImage(ctx, job, step, startedAt);
                }
                default -> throw new PipelineExecutionException("Unsupported step type " + step.type());
            }
        } catch (CommandTimeoutException e) {
            ctx.logs().log("STEP FAILED: " + step.name() + " timed out after " + e.getTimeoutMs() + " ms");
            return stepResult(step, JobStatus.TIMED_OUT, -1, "", "", startedAt, Instant.now(),
                    "Command timed out after " + e.getTimeoutMs() + " ms");
        } catch (SecurityViolationException e) {
            ctx.logs().log("STEP BLOCKED: " + step.name() + " - " + e.getMessage());
            log.warn("Step blocked by security policy: {}", e.getMessage());
            return stepResult(step, JobStatus.FAILED, -1, "", "", startedAt, Instant.now(), e.getMessage());
        } catch (CommandExecutionException | PipelineExecutionException e) {
            ctx.logs().log("STEP ERROR: " + step.name() + " - " + e.getMessage());
            throw e;
        } finally {
            MdcContext.remove(com.cicd.platform.worker.logging.MdcContext.STEP);
        }
    }

    private StepResult executeBuildImage(ExecutionContext ctx, JobDefinition job, StepDefinition step,
                                         Instant startedAt) {
        log.warn("buildImage executes the repository Dockerfile on the host docker daemon "
                + "- enabled only for controlled environments");
        String imageName = step.command();
        String command = "docker build -t " + imageName + " .";
        Path workdir = resolveWorkDir(ctx, job);
        CommandResult result = commandExecutor.execute(command, ctx.workspace().root(), workdir,
                job.workingDirectory() == null ? "." : job.workingDirectory(), Map.of(),
                props.getCommandTimeoutMs(), ctx.job().jobId());
        recordOutput(ctx, result);
        return stepResult(step, mapStatus(result), result.exitCode(), result.stdout(), result.stderr(),
                startedAt, result.completedAt(),
                result.status() == com.cicd.platform.worker.domain.CommandStatus.FAILED
                        ? "docker build failed with exit code " + result.exitCode() : null);
    }

    private Map<String, String> effectiveEnvironment(ExecutionContext ctx, JobDefinition job) {
        Map<String, String> env = new LinkedHashMap<>();
        env.putAll(ctx.trustedEnvironment(job.env(), props.getBaseEnvironment()));
        env.put("WORKSPACE", ctx.workspace().root().toString());
        env.put("REPO_ROOT", ctx.workspace().repoDir().toString());
        env.put("CI", "true");
        return sandboxEnv.sanitize(env);
    }

    private Path resolveWorkDir(ExecutionContext ctx, JobDefinition job) {
        Path repo = ctx.workspace().repoDir();
        if (job.workingDirectory() == null || job.workingDirectory().isBlank()) {
            return repo;
        }
        String workDir = job.workingDirectory().trim();
        if (workDir.contains("..") || workDir.contains("~")) {
            throw new SecurityViolationException(
                    "workingDirectory contains path traversal: " + workDir);
        }
        Path workdir = repo.resolve(workDir).normalize();
        if (!workdir.startsWith(repo.normalize())) {
            throw new SecurityViolationException(
                    "workingDirectory escapes repository root: " + job.workingDirectory());
        }
        return workdir;
    }

    private void recordOutput(ExecutionContext ctx, CommandResult result) {
        String stdout = securityPolicy.redactSecrets(result.stdout(), ctx.job().environment());
        String stderr = securityPolicy.redactSecrets(result.stderr(), ctx.job().environment());
        ctx.logs().appendOutput(stdout);
        if (!stderr.isBlank()) {
            ctx.logs().appendOutput("STDERR:\n" + stderr);
        }
        ctx.logs().log("Command exit code: " + result.exitCode() + " in "
                + result.durationMs() + " ms");
    }

    private StepResult stepResult(StepDefinition step, JobStatus status, int exitCode,
                                  String stdout, String stderr, Instant startedAt, Instant completedAt,
                                  String error) {
        return new StepResult(step.name(), step.type(), step.command(), status, exitCode,
                startedAt, completedAt,
                completedAt == null || startedAt == null ? 0 : Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli()),
                stdout, stderr, error);
    }

    private JobStatus mapStatus(CommandResult result) {
        return switch (result.status()) {
            case SUCCESS -> JobStatus.SUCCESS;
            case FAILED -> JobStatus.FAILED;
            case TIMED_OUT -> JobStatus.TIMED_OUT;
        };
    }
}
