package com.cicd.platform.controlplane.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        HashSet<GrantedAuthority> authorities = new HashSet<>();
        collectRealmRoles(jwt.getClaimAsMap("realm_access"), authorities);
        collectClientRoles(jwt.getClaimAsMap("resource_access"), authorities);
        collectObjectRoles(jwt.getClaim("roles"), authorities);
        return new ArrayList<>(authorities);
    }

    private void collectRealmRoles(Map<String, Object> realmAccess, Collection<GrantedAuthority> authorities) {
        if (realmAccess != null) {
            collectObjectRoles(realmAccess.get("roles"), authorities);
        }
    }

    private void collectClientRoles(Map<String, Object> resourceAccess, Collection<GrantedAuthority> authorities) {
        if (resourceAccess == null) {
            return;
        }
        for (Object value : resourceAccess.values()) {
            if (!(value instanceof Map<?, ?> clientClaims)) {
                continue;
            }
            collectObjectRoles(clientClaims.get("roles"), authorities);
        }
    }

    private void collectObjectRoles(Object rolesClaim, Collection<GrantedAuthority> authorities) {
        if (!(rolesClaim instanceof List<?> roles)) {
            return;
        }
        for (Object role : roles) {
            if (!(role instanceof String name) || name.isBlank()) {
                continue;
            }
            authorities.add(new SimpleGrantedAuthority("ROLE_" + name.toUpperCase(Locale.ROOT)));
        }
    }
}