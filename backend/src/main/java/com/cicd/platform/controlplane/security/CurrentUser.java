package com.cicd.platform.controlplane.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public record CurrentUser(
        String subject,
        String username,
        String email,
        String displayName,
        Set<ApiRole> roles
) {
    public boolean isAdmin() {
        return roles.contains(ApiRole.ADMIN);
    }

    public boolean hasRole(ApiRole role) {
        return roles.contains(role);
    }

    public static CurrentUser from(Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        EnumSet<ApiRole> roles = EnumSet.noneOf(ApiRole.class);
        for (GrantedAuthority authority : authorities) {
            String value = authority.getAuthority();
            if (value == null || !value.startsWith("ROLE_")) {
                continue;
            }
            try {
                roles.add(ApiRole.valueOf(value.substring(5).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new CurrentUser(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                roles);
    }
}