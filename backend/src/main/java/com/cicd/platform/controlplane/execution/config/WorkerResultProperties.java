package com.cicd.platform.controlplane.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology consumed by the control plane for worker results.
 * Defaults mirror the worker's {@code cicd.*} topology configuration so the
 * two services integrate without extra wiring.
 */
@Configuration
@ConfigurationProperties(prefix = "execution.results")
public class WorkerResultProperties {

    private String exchange = "cicd.results.exchange";
    private String queue = "cicd.results";
    private String routingKey = "cicd.result";

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getQueue() { return queue; }
    public void setQueue(String queue) { this.queue = queue; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
}