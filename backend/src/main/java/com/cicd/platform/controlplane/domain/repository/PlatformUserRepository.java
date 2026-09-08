package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.PlatformUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {
    Optional<PlatformUser> findByKeycloakSubject(String keycloakSubject);
}