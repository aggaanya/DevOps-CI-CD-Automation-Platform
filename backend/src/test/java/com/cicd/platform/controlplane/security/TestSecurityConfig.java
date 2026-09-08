package com.cicd.platform.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Test-only security configuration.
 *
 * <p>Runs only under the {@code test} profile. Production security
 * ({@link SecurityConfig}, {@code @Profile("!test")}) is untouched. Without a
 * dedicated filter chain in tests, Spring Boot falls back to its default
 * restrictive chain and existing API integration tests would be rejected with
 * 401/403. This chain disables CSRF, permits all requests and seeds an
 * authenticated global-admin principal so controller and service behaviour can
 * be exercised without fabricating JWTs per test.</p>
 */
@Configuration
@Profile("test")
@EnableWebSecurity
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        OncePerRequestFilter seedAuthentication = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    Jwt jwt = Jwt.withTokenValue("test-token")
                            .header("alg", "none")
                            .subject("test-admin-subject")
                            .claim("preferred_username", "test-admin")
                            .claim("email", "test-admin@example.com")
                            .claim("name", "Test Admin")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(3600))
                            .build();
                    SecurityContextHolder.getContext().setAuthentication(
                            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                }
                filterChain.doFilter(request, response);
            }
        };

        http.csrf(csrf -> csrf.disable())
                .addFilterAfter(seedAuthentication, SecurityContextHolderFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}