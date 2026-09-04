package com.cicd.platform.controlplane.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology used by the control plane to submit ad-hoc execution jobs
 * to standalone workers. Defaults mirror the worker's {@code cicd.*} topology
 * so the two services integrate without extra wiring.
 */
@Configuration
@ConfigurationProperties(prefix = "execution.trigger")
public class JobTriggerProperties {

    private String exchange = "cicd.jobs.exchange";
    private String routingKey = "cicd.job.submitted";

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
}