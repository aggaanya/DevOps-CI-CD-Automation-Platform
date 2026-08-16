package com.cicd.platform.worker.sandbox;

import com.cicd.platform.worker.domain.CommandResult;
import com.cicd.platform.worker.exception.CommandExecutionException;
import com.cicd.platform.worker.exception.CommandTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs commands as child OS processes on the worker host. The untrusted
 * command never executes inside the worker JVM, but this mode shares the
 * host kernel with the workload and is therefore NOT a hard security
 * boundary. It is intended for local development and for workloads that
 * can later be moved behind {@link DockerExecutionSandbox}.
 *
 * <p>Environment is deliberately whitelisted: only a small, safe base set
 * plus the job-provided variables are passed to the child process.</p>
 */
public class ProcessExecutionSandbox implements ExecutionSandbox {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutionSandbox.class);

    private static final Set<String> BASE_ENV_KEYS = Set.of(
            "PATH", "SystemRoot", "SystemDrive", "WINDIR", "TEMP", "TMP",
            "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "HOME", "USERPROFILE", "TMPDIR", "ComSpec");

    private final long maxLogBytes;
    private final boolean windows;
    private final Map<Path, Set<Process>> running = new ConcurrentHashMap<>();

    public ProcessExecutionSandbox(long maxLogBytes) {
        this.maxLogBytes = maxLogBytes;
        this.windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Override
    public CommandResult execute(SandboxRequest request) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(windows ? "cmd.exe" : "sh");
        commandLine.add(windows ? "/c" : "-c");
        commandLine.add(request.command());

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.directory(request.workingDirectoryHost().toFile());
        builder.redirectErrorStream(false);
        Map<String, String> childEnv = builder.environment();
        childEnv.clear();
        request.environment().forEach(childEnv::put);
        for (String key : BASE_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !childEnv.containsKey(key)) {
                childEnv.put(key, value);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Executing [{}] in {}", request.command(), request.workingDirectoryHost());
        }

        Instant startedAt = Instant.now();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CommandExecutionException("Failed to start command: " + e.getMessage(), e);
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        register(request.workspaceHost(), process);
        try {
            StreamCapturer stdout = StreamCapturer.start(process.getInputStream(), maxLogBytes, "stdout-capture");
            StreamCapturer stderr = StreamCapturer.start(process.getErrorStream(), maxLogBytes, "stderr-capture");

            boolean finished = process.waitFor(request.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                killTree(process, cancelled);
                Instant completedAt = Instant.now();
                stdout.join(Duration.ofSeconds(2).toMillis());
                stderr.join(Duration.ofSeconds(2).toMillis());
                log.warn("Command timed out after {} ms: {}", request.timeoutMs(), request.command());
                throw new CommandTimeoutException("Command timed out after " + request.timeoutMs() + " ms",
                        request.timeoutMs());
            }
            stdout.join();
            stderr.join();
            int exitCode = process.exitValue();
            Instant completedAt = Instant.now();
            if (exitCode == 0) {
                return CommandResult.success(exitCode, stdout.getContent(), stderr.getContent(),
                        startedAt, completedAt);
            }
            return CommandResult.failed(exitCode, stdout.getContent(), stderr.getContent(),
                    startedAt, completedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process, cancelled);
            throw new CommandExecutionException("Command interrupted", e);
        } finally {
            unregister(request.workspaceHost(), process);
        }
    }

    @Override
    public void terminate(Path workspaceHostRoot) {
        Set<Process> processes = running.get(workspaceHostRoot);
        if (processes == null) {
            return;
        }
        for (Process p : new ArrayList<>(processes)) {
            try {
                p.destroyForcibly();
            } catch (Exception e) {
                log.debug("Failed to terminate process {}", p.pid(), e);
            }
        }
    }

    private void register(Path workspaceRoot, Process process) {
        running.computeIfAbsent(workspaceRoot, k -> ConcurrentHashMap.newKeySet()).add(process);
    }

    private void unregister(Path workspaceRoot, Process process) {
        Set<Process> processes = running.get(workspaceRoot);
        if (processes != null) {
            processes.remove(process);
        }
    }

    private void killTree(Process process, AtomicBoolean cancelled) {
        cancelled.set(true);
        try {
            process.descendants().forEach(p -> {
                try {
                    p.destroyForcibly();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

}
