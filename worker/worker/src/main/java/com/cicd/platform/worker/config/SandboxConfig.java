package com.cicd.platform.worker.config;

import com.cicd.platform.worker.sandbox.DockerExecutionSandbox;
import com.cicd.platform.worker.sandbox.ExecutionSandbox;
import com.cicd.platform.worker.sandbox.ProcessExecutionSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link ExecutionSandbox} implementation from configuration.
 * Local development uses the host process sandbox; production should use
 * the docker sandbox (and later Kubernetes/Azure container jobs).
 */
@Configuration
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    private final WorkerProperties props;

    public SandboxConfig(WorkerProperties props) {
        this.props = props;
    }

    @Bean
    public ExecutionSandbox executionSandbox() {
        String mode = props.getSandbox().getMode().toLowerCase();
        return switch (mode) {
            case "process" -> {
                log.warn("Execution sandbox mode = PROCESS. Untrusted commands run as child OS processes "
                        + "on the worker host. This is NOT a hard isolation boundary.");
                yield new ProcessExecutionSandbox(props.getMaxLogBytes());
            }
            case "docker" -> new DockerExecutionSandbox(props);
            default -> throw new IllegalStateException(
                    "Unknown sandbox mode '" + mode + "'. Supported: process, docker");
        };
    }
}
