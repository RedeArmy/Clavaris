package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.WebhookEndpoint} and {@link
 * WebhookEndpointEntity}.
 */
@SuppressWarnings("PMD.ShortVariable")
@Repository
class JpaWebhookEndpointRepository implements WebhookEndpointRepository {

  private final SpringDataWebhookEndpointJpaRepository endpoints;
  private final ObjectMapper objectMapper;

  /* package */ JpaWebhookEndpointRepository(
      final SpringDataWebhookEndpointJpaRepository endpoints, final ObjectMapper objectMapper) {
    this.endpoints = endpoints;
    this.objectMapper = objectMapper;
  }

  @Override
  public void save(final WebhookEndpoint endpoint) {
    endpoints.save(
        new WebhookEndpointEntity(
            endpoint.id(),
            endpoint.organizationId(),
            endpoint.url(),
            endpoint.description(),
            objectMapper.writeValueAsString(endpoint.subscribedEventTypes()),
            endpoint.currentSecretEncrypted(),
            endpoint.previousSecretEncrypted(),
            endpoint.previousSecretExpiresAt(),
            endpoint.active(),
            endpoint.createdAt()));
  }

  @Override
  public Optional<WebhookEndpoint> findById(final UUID id) {
    return endpoints.findById(id).map(this::toDomain);
  }

  @Override
  public List<WebhookEndpoint> findAllByOrganizationId(final UUID organizationId) {
    return endpoints.findAllByOrganizationId(organizationId).stream().map(this::toDomain).toList();
  }

  @Override
  public List<WebhookEndpoint> findActiveByOrganizationIdAndEventType(
      final UUID organizationId, final String eventType) {
    // In-memory filter on subscribedEventTypes — this table's own migration comment already flags
    // a JSON-containment index as the real fix once volume justifies it; every Organization's own
    // endpoint count is small (a handful, not thousands), so this is "correct and simple first"
    // (same posture TD-SEC-031's own Javadoc already applies to a comparably-shaped lookup).
    return endpoints.findAllByOrganizationIdAndActiveTrue(organizationId).stream()
        .map(this::toDomain)
        .filter(endpoint -> endpoint.subscribesTo(eventType))
        .toList();
  }

  private WebhookEndpoint toDomain(final WebhookEndpointEntity entity) {
    return WebhookEndpoint.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getUrl(),
        entity.getDescription(),
        readJsonArray(entity.getSubscribedEventTypes()),
        entity.getCurrentSecretEncrypted(),
        entity.getPreviousSecretEncrypted(),
        entity.getPreviousSecretExpiresAt(),
        entity.isActive(),
        entity.getCreatedAt());
  }

  private List<String> readJsonArray(final String json) {
    return Arrays.asList(objectMapper.readValue(json, String[].class));
  }
}
