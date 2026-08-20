package com.cicd.platform.controlplane.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CI/CD Control Plane API")
                        .description("Enterprise CI/CD Automation Platform — Pipeline orchestration, "
                                + "execution management, and deployment tracking.")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("CI/CD Platform Team")
                                .email("platform@example.com"))
                        .license(new License()
                                .name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local Development")));
    }
}
