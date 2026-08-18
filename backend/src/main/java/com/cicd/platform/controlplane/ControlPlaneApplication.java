package com.cicd.platform.controlplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ControlPlaneApplication {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ControlPlaneApplication.class);
        app.run(args);
        log.info("CI/CD Control Plane started successfully");
    }
}
