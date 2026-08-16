package com.cicd.platform.worker.sandbox;

import com.cicd.platform.worker.domain.CommandResult;

import java.nio.file.Path;

/**
 * Abstraction over the execution mechanism used to run untrusted commands.
 *
 * <p>Concrete implementations run the command either as a child OS process
 * (never inside the worker JVM) or inside an isolated Docker container.
 * The interface is designed so the mechanism can later be replaced by
 * Kubernetes Jobs, Azure Container Apps Jobs or dedicated VM runners.</p>
 *
 * <p>Untrusted repository commands must never execute inside the worker JVM.</p>
 */
public interface ExecutionSandbox {

    /**
     * Executes a command inside the sandbox and returns a structured result.
     *
     * @param request the fully validated sandbox request.
     * @return the command result including captured streams and exit code.
     */
    CommandResult execute(SandboxRequest request);

    /**
     * Best-effort termination of every command currently running under the
     * given workspace (used by the pipeline duration watchdog).
     */
    void terminate(Path workspaceHostRoot);
}
