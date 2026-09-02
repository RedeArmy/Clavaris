package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWebhookEndpointJpaRepository
    extends JpaRepository<WebhookEndpointEntity, UUID> {

  List<WebhookEndpointEntity> findAllByOrganizationId(UUID organizationId);

  List<WebhookEndpointEntity> findAllByOrganizationIdAndActiveTrue(UUID organizationId);
}
