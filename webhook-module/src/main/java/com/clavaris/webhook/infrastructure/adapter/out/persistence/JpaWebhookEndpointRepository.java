package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.WebhookEndpoint} and {@link
 * WebhookEndpointEntity}.
 *
 * <p>TD-PERF-019: {@code insert} calls {@link EntityManager#persist} directly — see {@code
 * WebhookEndpointRepository#insert}'s own Javadoc for which call site that's safe for and why
 * {@code save} itself is unchanged.
 */
@SuppressWarnings("PMD.ShortVariable")
@Repository
class JpaWebhookEndpointRepository implements WebhookEndpointRepository {

  private final SpringDataWebhookEndpointJpaRepository endpoints;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;

  /* package */ JpaWebhookEndpointRepository(
      final SpringDataWebhookEndpointJpaRepository endpoints,
      final ObjectMapper objectMapper,
      final EntityManager entityManager) {
    this.endpoints = endpoints;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
  }

  @Override
  public void save(final WebhookEndpoint endpoint) {
    endpoints.save(toEntity(endpoint));
  }

  @Override
  @Transactional
  public void insert(final WebhookEndpoint endpoint) {
    entityManager.persist(toEntity(endpoint));
  }

  private WebhookEndpointEntity toEntity(final WebhookEndpoint endpoint) {
    return new WebhookEndpointEntity(
        endpoint.id(),
        endpoint.organizationId(),
        endpoint.url(),
        endpoint.description(),
        objectMapper.writeValueAsString(endpoint.subscribedEventTypes()),
        endpoint.currentSecretEncrypted(),
        endpoint.previousSecretEncrypted(),
        endpoint.previousSecretExpiresAt(),
        endpoint.active(),
        endpoint.createdAt());
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
    return findActiveByOrganizationId(organizationId).stream()
        .filter(endpoint -> endpoint.subscribesTo(eventType))
        .toList();
  }

  @Override
  public List<WebhookEndpoint> findActiveByOrganizationId(final UUID organizationId) {
    return endpoints.findAllByOrganizationIdAndActiveTrue(organizationId).stream()
        .map(this::toDomain)
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
