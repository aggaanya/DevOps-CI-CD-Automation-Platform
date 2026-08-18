package com.cicd.platform.controlplane.domain.repository;

import com.cicd.platform.controlplane.domain.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
    Optional<WebhookEvent> findByProviderAndDeliveryId(String provider, String deliveryId);
    boolean existsByProviderAndDeliveryId(String provider, String deliveryId);
}
