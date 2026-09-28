package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.ExecutionContext;
import com.cicd.platform.controlplane.execution.StepResult;
import com.cicd.platform.controlplane.pipeline.PipelineConfigMapper.StepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Executes one job attempt inside its workspace.
 *
 * <p>Two command sources, in priority order:
 * <ol>
 *   <li><b>Declared steps</b> — when the pipeline YAML gives the job explicit
 *       {@code steps}, they run in declaration order and the job fails at the first
 *       step that fails. This is what makes a job's cost explicit and therefore
 *       measurable, which the parallel-execution benchmark depends on.</li>
 *   <li><b>Build-system auto-detection</b> — the historical behaviour, kept intact
 *       for every pipeline authored before steps existed.</li>
 * </ol>
 *
 * <p>Commands are launched with {@link ProcessBuilder} in the control-plane
 * process. There is no OS-level sandbox here; see
 * {@code docs/benchmarks/worker-isolation.md} for exactly what isolation does and
 * does not exist.
 */
@Component
public class WorkerExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkerExecutor.class);

    private final GitOperations gitOperations;
    private final StepExecutor stepExecutor;
    private final ExecutionLogger executionLogger;

    public WorkerExecutor(GitOperations gitOperations,
                          StepExecutor stepExecutor,
                          ExecutionLogger executionLogger) {
        this.gitOperations = gitOperations;
        this.stepExecutor = stepExecutor;
        this.executionLogger = executionLogger;
    }

    public boolean executeJob(ExecutionContext ctx) {
        executionLogger.logJobStart(ctx);

        try {
            Path workDir = ctx.workDir();
            Path logsDir = ctx.logsDir();

            if (ctx.gitUrl() != null && !ctx.gitUrl().isBlank()) {
                boolean initialized = gitOperations.initializeWorkspace(
                        workDir, ctx.gitUrl(), ctx.branch(), ctx.commitSha());
                if (!initialized) {
                    log.error("Failed to initialize workspace for job {}", ctx.jobName());
                    executionLogger.logError("Failed to initialize workspace", null);
                    return false;
                }
            }

            List<StepDefinition> steps = ctx.steps();
            if (steps == null || steps.isEmpty()) {
                return executeSingleCommandJob(ctx, workDir, logsDir);
            }
            return executeDeclaredSteps(ctx, steps, workDir, logsDir);
        } catch (Exception e) {
            log.error("Error executing job {}", ctx.jobName(), e);
            executionLogger.logError("Job execution failed", e);
            return false;
        }
    }

    private boolean executeSingleCommandJob(ExecutionContext ctx, Path workDir, Path logsDir) {
        String command = buildCommand(ctx);
        StepResult stepResult = stepExecutor.executeStep(ctx, ctx.jobType().name(), command);

        Path logFile = logsDir.resolve(safeFileName(ctx.jobType().name()) + ".log");
        writeLog(logFile, stepResult.stdout());

        executionLogger.logStepExecution(ctx.jobType().name(), stepResult);
        executionLogger.logJobComplete(ctx, stepResult.success(), stepResult.exitCode());

        return stepResult.success();
    }

    /**
     * Runs declared steps in order, stopping at the first failure.
     *
     * <p>Every step gets its own log file and its own timing record, which is what
     * lets the benchmark prove that two independent jobs overlapped in wall-clock
     * time rather than inferring parallelism from a total duration.
     */
    private boolean executeDeclaredSteps(ExecutionContext ctx, List<StepDefinition> steps,
                                         Path workDir, Path logsDir) {
        StringBuilder transcript = new StringBuilder();
        boolean success = true;
        StepResult last = null;
        int index = 0;

        for (StepDefinition step : steps) {
            String stepName = step.name() == null || step.name().isBlank()
                    ? "step-" + (index + 1) : step.name();
            index++;

            log.info("[STEP_START] jobId={}, jobName={}, step={}, of {}",
                    ctx.jobId(), ctx.jobName(), stepName, steps.size());

            StepResult result = stepExecutor.executeStep(ctx, stepName, step.run());
            last = result;

            transcript.append("=== step: ").append(stepName)
                    .append(" exitCode=").append(result.exitCode()).append(" ===\n")
                    .append(result.stdout() == null ? "" : result.stdout())
                    .append('\n');

            Path stepLog = logsDir.resolve(safeFileName(stepName) + ".log");
            writeLog(stepLog, result.stdout());

            executionLogger.logStepExecution(stepName, result);

            if (!result.success()) {
                success = false;
                log.warn("[STEP_FAILED] jobId={}, jobName={}, step={}, exitCode={}",
                        ctx.jobId(), ctx.jobName(), stepName, result.exitCode());
                break;
            }
            log.info("[STEP_FINISHED] jobId={}, jobName={}, step={}, exitCode={}",
                    ctx.jobId(), ctx.jobName(), stepName, result.exitCode());
        }

        writeLog(logsDir.resolve(safeFileName(ctx.jobName()) + ".log"), transcript.toString());
        executionLogger.logJobComplete(ctx, success, last == null ? -1 : last.exitCode());
        return success;
    }

    private void writeLog(Path logFile, String content) {
        try {
            Files.writeString(logFile, content == null ? "" : content);
        } catch (Exception e) {
            log.warn("Failed to write step log to {}", logFile, e);
        }
    }

    private String safeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "job";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String buildCommand(ExecutionContext ctx) {
        Path workDir = ctx.workDir();

        return switch (ctx.jobType()) {
            case BUILD -> detectBuildCommand(workDir);
            case TEST -> detectTestCommand(workDir);
            case SCAN -> detectScanCommand(workDir);
            case DEPLOY -> detectDeployCommand(workDir);
            case PACKAGE -> detectPackageCommand(workDir);
            case CUSTOM -> detectCustomCommand(workDir);
        };
    }

    private String detectBuildCommand(Path workDir) {
        if (Files.exists(workDir.resolve("pom.xml"))) {
            return "mvn clean install -DskipTests";
        }
        if (Files.exists(workDir.resolve("build.gradle")) || Files.exists(workDir.resolve("build.gradle.kts"))) {
            return "./gradlew build -x test";
        }
        if (Files.exists(workDir.resolve("package.json"))) {
            return "npm ci && npm run build";
        }
        if (Files.exists(workDir.resolve("Makefile"))) {
            return "make build";
        }
        return "echo 'No recognized build system found'";
    }

    private String detectTestCommand(Path workDir) {
        if (Files.exists(workDir.resolve("pom.xml"))) {
            return "mvn test";
        }
        if (Files.exists(workDir.resolve("build.gradle")) || Files.exists(workDir.resolve("build.gradle.kts"))) {
            return "./gradlew test";
        }
        if (Files.exists(workDir.resolve("package.json"))) {
            return "npm test";
        }
        if (Files.exists(workDir.resolve("Makefile"))) {
            return "make test";
        }
        return "echo 'No recognized test framework found'";
    }

    private String detectScanCommand(Path workDir) {
        if (Files.exists(workDir.resolve("pom.xml"))) {
            return "mvn checkstyle:check spotbugs:check";
        }
        if (Files.exists(workDir.resolve("package.json"))) {
            return "npm run lint";
        }
        return "echo 'No recognized scan tool found'";
    }

    private String detectDeployCommand(Path workDir) {
        if (Files.exists(workDir.resolve("docker-compose.yml"))
                || Files.exists(workDir.resolve("docker-compose.yaml"))) {
            return "docker-compose up -d";
        }
        if (Files.exists(workDir.resolve("Dockerfile"))) {
            return "docker build -t app .";
        }
        return "echo 'No recognized deploy target found'";
    }

    private String detectPackageCommand(Path workDir) {
        if (Files.exists(workDir.resolve("pom.xml"))) {
            return "mvn package -DskipTests";
        }
        if (Files.exists(workDir.resolve("build.gradle")) || Files.exists(workDir.resolve("build.gradle.kts"))) {
            return "./gradlew bootJar";
        }
        if (Files.exists(workDir.resolve("package.json"))) {
            return "npm pack";
        }
        return "echo 'No recognized packaging tool found'";
    }

    private String detectCustomCommand(Path workDir) {
        if (Files.exists(workDir.resolve("Makefile"))) {
            return "make all";
        }
        return "echo 'Custom job: no default command'";
    }
}
