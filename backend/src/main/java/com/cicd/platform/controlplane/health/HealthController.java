package com.cicd.platform.controlplane.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final RabbitTemplate rabbitTemplate;

    public HealthController(DataSource dataSource, RabbitTemplate rabbitTemplate) {
        this.dataSource = dataSource;
        this.rabbitTemplate = rabbitTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        Map<String, String> components = new LinkedHashMap<>();
        boolean allUp = true;

        components.put("controlPlane", "UP");

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            components.put("database", "UP — " + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            components.put("database", "DOWN — " + e.getMessage());
            allUp = false;
        }

        try {
            rabbitTemplate.getConnectionFactory().createConnection().close();
            components.put("rabbitmq", "UP");
        } catch (Exception e) {
            log.warn("RabbitMQ health check failed: {}", e.getMessage());
            components.put("rabbitmq", "DOWN — " + e.getMessage());
            allUp = false;
        }

        if (allUp) {
            return ResponseEntity.ok(HealthResponse.up(components));
        } else {
            return ResponseEntity.status(503).body(HealthResponse.degraded(components));
        }
    }
}
