package com.cicd.platform.worker.sandbox;

import com.cicd.platform.worker.config.WorkerProperties;
import com.cicd.platform.worker.domain.CommandResult;
import com.cicd.platform.worker.exception.CommandExecutionException;
import com.cicd.platform.worker.exception.CommandTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Executes each command inside a fresh, disposable Docker container.
 *
 * <p>Security posture (defence in depth against untrusted repository code):</p>
 * <ul>
 *   <li>workspace is mounted read/write but the container is non-privileged;</li>
 *   <li>{@code --cap-drop ALL} removes all Linux capabilities;</li>
 *   <li>{@code --security-opt no-new-privileges} blocks setuid escalation;</li>
 *   <li>the host docker socket is never mounted into the sandbox;</li>
 *   <li>only allow-listed environment variables are passed via {@code -e};</li>
 *   <li>the container is removed on exit ({@code --rm}).</li>
 * </ul>
 *
 * <p>The docker CLI is invoked through a {@code ProcessBuilder} argument
 * list (no host shell), so repository strings can never inject host shell
 * syntax. Commands run under the container's own shell ({@code sh -c}).</p>
 */
public class DockerExecutionSandbox implements ExecutionSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutionSandbox.class);

    private static final String LABEL_KEY = "cicd.workspace";
    private static final String LABEL_JOB = "cicd.job";

    private final WorkerProperties props;
    private final long maxLogBytes;
    private final Map<Path, Set<String>> runningContainers = new ConcurrentHashMap<>();

    public DockerExecutionSandbox(WorkerProperties props) {
        this.props = props;
        this.maxLogBytes = props.getMaxLogBytes();
    }

    @Override
    public CommandResult execute(SandboxRequest request) {
        String image = props.getSandbox().getDockerImage();
        String containerName = "cicd-sandbox-" + UUID.randomUUID().toString().substring(0, 12);
        String workspaceLabel = labelValue(request.workspaceHost());

        List<String> commandLine = new ArrayList<>(List.of("docker", "run", "--rm"));
        commandLine.add("--name");
        commandLine.add(containerName);
        commandLine.add("--cap-drop=ALL");
        commandLine.add("--security-opt");
        commandLine.add("no-new-privileges");
        commandLine.add("--label");
        commandLine.add(LABEL_KEY + "=" + workspaceLabel);
        commandLine.add("--label");
        commandLine.add(LABEL_JOB + "=" + request.jobId());

        if (props.getSandbox().getRunAsUser() != null && !props.getSandbox().getRunAsUser().isBlank()) {
            commandLine.add("--user");
            commandLine.add(props.getSandbox().getRunAsUser());
        }
        if (props.getSandbox().getDockerNetwork() != null && !props.getSandbox().getDockerNetwork().isBlank()) {
            commandLine.add("--network");
            commandLine.add(props.getSandbox().getDockerNetwork());
        }
        commandLine.add("-v");
        commandLine.add(request.workspaceHost().toAbsolutePath() + ":" + props.getSandbox().getContainerWorkspacePath());

        String containerWorkDir = containerPath(request, props.getSandbox().getContainerWorkspacePath());
        commandLine.add("-w");
        commandLine.add(containerWorkDir);

        commandLine.add("-e");
        commandLine.add("CI=true");
        request.environment().forEach((k, v) -> {
            commandLine.add("-e");
            commandLine.add(k + "=" + v);
        });

        commandLine.add(image);
        commandLine.add("sh");
        commandLine.add("-c");
        commandLine.add(request.command());

        if (log.isDebugEnabled()) {
            log.debug("Docker sandbox command: {}", safeDescribe(commandLine));
        }

        register(request.workspaceHost(), containerName);
        try {
            return runDockerProcess(commandLine, request, containerName);
        } finally {
            unregister(request.workspaceHost(), containerName);
        }
    }

    private CommandResult runDockerProcess(List<String> commandLine, SandboxRequest request, String containerName) {
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.redirectErrorStream(false);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        if (System.getenv("DOCKER_HOST") != null) {
            env.put("DOCKER_HOST", System.getenv("DOCKER_HOST"));
        }
        if (System.getenv("DOCKER_CERT_PATH") != null) {
            env.put("DOCKER_CERT_PATH", System.getenv("DOCKER_CERT_PATH"));
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CommandExecutionException("Failed to start docker sandbox: " + e.getMessage(), e);
        }

        try {
            StreamCapturer stdout = StreamCapturer.start(process.getInputStream(), maxLogBytes, "docker-stdout");
            StreamCapturer stderr = StreamCapturer.start(process.getErrorStream(), maxLogBytes, "docker-stderr");

            long timeout = request.timeoutMs() + props.getSandbox().getDockerPullTimeoutMs();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                forceRemoveContainer(containerName);
                stdout.join(2000);
                stderr.join(2000);
                throw new CommandTimeoutException("Docker sandbox command timed out after " + request.timeoutMs() + " ms",
                        request.timeoutMs());
            }
            stdout.join();
            stderr.join();
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return CommandResult.success(exitCode, stdout.getContent(), stderr.getContent(),
                        null, null);
            }
            return CommandResult.failed(exitCode, stdout.getContent(), stderr.getContent(),
                    null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            forceRemoveContainer(containerName);
            throw new CommandExecutionException("Docker sandbox interrupted", e);
        }
    }

    @Override
    public void terminate(Path workspaceHostRoot) {
        Set<String> names = runningContainers.get(workspaceHostRoot);
        if (names == null) {
            return;
        }
        for (String name : new ArrayList<>(names)) {
            forceRemoveContainer(name);
        }
    }

    private void register(Path workspaceRoot, String containerName) {
        runningContainers.computeIfAbsent(workspaceRoot, k -> ConcurrentHashMap.newKeySet()).add(containerName);
    }

    private void unregister(Path workspaceRoot, String containerName) {
        Set<String> names = runningContainers.get(workspaceRoot);
        if (names != null) {
            names.remove(containerName);
        }
    }

    private void forceRemoveContainer(String containerName) {
        try {
            Process p = new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Failed to remove sandbox container {}", containerName, e);
        }
    }

    private String containerPath(SandboxRequest request, String containerWorkspacePath) {
        String rel = request.workingDirectoryRelative();
        if (rel == null || rel.isBlank() || rel.equals(".")) {
            return containerWorkspacePath;
        }
        String normalized = rel.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return containerWorkspacePath + "/" + normalized;
    }

    private String labelValue(Path path) {
        String raw = path.toAbsolutePath().toString().replace('\\', '_').replace(':', '_');
        return raw.substring(Math.max(0, raw.length() - 60));
    }

    private String safeDescribe(List<String> commandLine) {
        StringBuilder sb = new StringBuilder();
        for (String token : commandLine) {
            if (token.contains("=") && (token.startsWith("-e") || token.startsWith("--env"))) {
                int idx = token.indexOf('=');
                sb.append(token, 0, idx + 1).append("<redacted> ").append(' ');
            } else {
                sb.append(token).append(' ');
            }
        }
        return sb.toString();
    }
}
