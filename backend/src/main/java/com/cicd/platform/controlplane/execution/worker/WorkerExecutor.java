package com.cicd.platform.controlplane.execution.worker;

import com.cicd.platform.controlplane.execution.ExecutionContext;
import com.cicd.platform.controlplane.execution.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

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

            String command = buildCommand(ctx);
            StepResult stepResult = stepExecutor.executeStep(ctx, ctx.jobType().name(), command);

            Path logFile = logsDir.resolve(ctx.jobType().name().toLowerCase() + ".log");
            try {
                Files.writeString(logFile, stepResult.stdout());
            } catch (Exception e) {
                log.warn("Failed to write step log to {}", logFile, e);
            }

            executionLogger.logStepExecution(ctx.jobType().name(), stepResult);
            executionLogger.logJobComplete(ctx, stepResult.success(), stepResult.exitCode());

            return stepResult.success();
        } catch (Exception e) {
            log.error("Error executing job {}", ctx.jobName(), e);
            executionLogger.logError("Job execution failed", e);
            return false;
        }
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
