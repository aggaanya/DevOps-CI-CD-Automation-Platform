package com.cicd.platform.worker.observability;

import com.cicd.platform.worker.config.WorkerProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Worker health: rabbit connection, workspace writability, worker id.
 */
@Component
public class WorkerHealthIndicator implements HealthIndicator {

    private final ConnectionFactory connectionFactory;
    private final WorkerProperties props;
    private final ExecutionMetrics metrics;

    public WorkerHealthIndicator(ConnectionFactory connectionFactory, WorkerProperties props, ExecutionMetrics metrics) {
        this.connectionFactory = connectionFactory;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        builder.withDetail("workerId", props.getId());
        builder.withDetail("runningJobs", metrics.running());

        boolean workspaceWritable = checkWorkspace();
        boolean rabbitUp = checkRabbit();
        builder.withDetail("workspaceWritable", workspaceWritable);
        builder.withDetail("rabbitUp", rabbitUp);

        if (!workspaceWritable || !rabbitUp) {
            builder.down();
        }
        return builder.build();
    }

    private boolean checkWorkspace() {
        try {
            Path root = props.getWorkspaceRoot();
            Files.createDirectories(root);
            return Files.isWritable(root);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkRabbit() {
        try {
            var connection = connectionFactory.createConnection();
            try {
                return connection.isOpen();
            } finally {
                connection.close();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
