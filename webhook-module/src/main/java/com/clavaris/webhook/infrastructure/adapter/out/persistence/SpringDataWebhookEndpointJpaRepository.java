package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWebhookEndpointJpaRepository
    extends JpaRepository<WebhookEndpointEntity, UUID> {

  List<WebhookEndpointEntity> findAllByOrganizationId(UUID organizationId);

  // TD-PERF-020: backs WebhookEndpointRepository#findPageByOrganizationId.
  Page<WebhookEndpointEntity> findAllByOrganizationId(UUID organizationId, Pageable pageable);

  List<WebhookEndpointEntity> findAllByOrganizationIdAndActiveTrue(UUID organizationId);

  void deleteAllByOrganizationId(UUID organizationId);
}
