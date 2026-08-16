package com.cicd.platform.worker.command;

import com.cicd.platform.worker.domain.CommandResult;
import com.cicd.platform.worker.sandbox.ExecutionSandbox;
import com.cicd.platform.worker.sandbox.SandboxRequest;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * Thin facade that hands validated commands to the configured
 * {@link ExecutionSandbox}. Keeping this separate lets the executors stay
 * agnostic about whether commands run as host processes or in containers.
 */
@Component
public class CommandExecutor {

    private final ExecutionSandbox sandbox;

    public CommandExecutor(ExecutionSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public CommandResult execute(String command, Path workspaceRoot, Path workingDirectory,
                                 String workingDirectoryRelative, Map<String, String> environment,
                                 long timeoutMs, String jobId) {
        SandboxRequest request = new SandboxRequest(workspaceRoot, workingDirectory,
                workingDirectoryRelative, command, environment, timeoutMs, jobId);
        return sandbox.execute(request);
    }

    public void terminate(Path workspaceRoot) {
        sandbox.terminate(workspaceRoot);
    }
}
