package com.cicd.platform.controlplane.security;

import com.cicd.platform.controlplane.domain.entity.PlatformUser;
import com.cicd.platform.controlplane.domain.repository.PlatformUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthUserService {

    private final PlatformUserRepository platformUserRepository;

    public AuthUserService(PlatformUserRepository platformUserRepository) {
        this.platformUserRepository = platformUserRepository;
    }

    @Transactional
    public PlatformUser findOrCreate(CurrentUser currentUser) {
        return platformUserRepository.findByKeycloakSubject(currentUser.subject())
                .map(existing -> {
                    boolean changed = false;
                    if (currentUser.username() != null && !currentUser.username().equals(existing.getUsername())) {
                        existing.setUsername(currentUser.username());
                        changed = true;
                    }
                    if (currentUser.email() != null && !currentUser.email().equals(existing.getEmail())) {
                        existing.setEmail(currentUser.email());
                        changed = true;
                    }
                    if (currentUser.displayName() != null && !currentUser.displayName().equals(existing.getDisplayName())) {
                        existing.setDisplayName(currentUser.displayName());
                        changed = true;
                    }
                    return changed ? platformUserRepository.save(existing) : existing;
                })
                .orElseGet(() -> {
                    PlatformUser user = new PlatformUser(
                            currentUser.subject(),
                            currentUser.username() != null ? currentUser.username() : currentUser.subject(),
                            currentUser.email(),
                            currentUser.displayName());
                    return platformUserRepository.save(user);
                });
    }

    @Transactional(readOnly = true)
    public Optional<PlatformUser> findBySubject(String keycloakSubject) {
        return platformUserRepository.findByKeycloakSubject(keycloakSubject);
    }
}