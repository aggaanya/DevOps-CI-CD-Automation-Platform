package com.cicd.platform.controlplane.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CurrentUserResolver {

    public Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return Optional.of(CurrentUser.from(jwtAuthentication.getToken(), jwtAuthentication.getAuthorities()));
        }
        return Optional.empty();
    }

    public CurrentUser require() {
        return current().orElseThrow(() -> new AccessDeniedException("Authentication required"));
    }
}