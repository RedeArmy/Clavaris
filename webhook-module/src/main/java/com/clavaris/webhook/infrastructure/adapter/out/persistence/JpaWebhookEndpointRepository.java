package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.common.infrastructure.adapter.out.persistence.AdvisoryLocks;
import com.clavaris.common.infrastructure.adapter.out.persistence.SpringDataKeysetPageMapper;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *
 * <p>PMD.TooManyMethods (TD-PERF-020's own {@code findPageByOrganizationId} pushed this past the
 * default threshold): every method here backs a real, distinct {@code WebhookEndpointRepository}
 * port method this module's use cases actually need — same "one port, several use cases" shape
 * {@code AccountRepository}'s own identical suppression documents, not a design smell to split up.
 */
@Repository
class JpaWebhookEndpointRepository implements WebhookEndpointRepository {

  private final SpringDataWebhookEndpointJpaRepository endpoints;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;
  private final JdbcTemplate jdbcTemplate;

  /* package */ JpaWebhookEndpointRepository(
      final SpringDataWebhookEndpointJpaRepository endpoints,
      final ObjectMapper objectMapper,
      final EntityManager entityManager,
      final JdbcTemplate jdbcTemplate) {
    this.endpoints = endpoints;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
    this.jdbcTemplate = jdbcTemplate;
  }

  // See WebhookEndpointRepository#lockForRegistration's own Javadoc. Plain JdbcTemplate, not a
  // Spring Data native @Query method — same reasoning
  // JpaWorkspaceMembershipRepository#lockForRoleChange's own identical comment documents: a
  // void-returning native SELECT without @Modifying is never sent to Postgres, and @Modifying
  // forces Hibernate through executeUpdate(), which rejects a SELECT-shaped statement.
  @Override
  public void lockForRegistration(final UUID organizationId) {
    AdvisoryLocks.lockForTransaction(
        jdbcTemplate, AdvisoryLocks.WEBHOOK_ENDPOINT_REGISTRATION, organizationId);
  }

  // See WebhookEndpointRepository#lockForRotation's own Javadoc. Same plain JdbcTemplate shape as
  // lockForRegistration above — a void-returning native SELECT without @Modifying is never sent
  // to Postgres, and @Modifying forces Hibernate through executeUpdate(), which rejects a
  // SELECT-shaped statement.
  @Override
  public void lockForRotation(final UUID endpointId) {
    AdvisoryLocks.lockForTransaction(
        jdbcTemplate, AdvisoryLocks.WEBHOOK_SECRET_ROTATION, endpointId);
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

  // Same @Transactional rationale as insert()'s own identical annotation above — a Spring Data
  // delete needs a real, currently-open transaction on this thread.
  @Override
  @Transactional
  public void delete(final WebhookEndpoint endpoint) {
    endpoints.deleteById(endpoint.id());
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
        endpoint.createdAt(),
        endpoint.consecutiveFailures(),
        endpoint.autoDisabledAt());
  }

  @Override
  public Optional<WebhookEndpoint> findById(final UUID id) {
    return endpoints.findById(id).map(this::toDomain);
  }

  @Override
  public List<WebhookEndpoint> findAllByIds(final Collection<UUID> ids) {
    return endpoints.findAllById(ids).stream().map(this::toDomain).toList();
  }

  @Override
  public List<WebhookEndpoint> findAllByOrganizationId(final UUID organizationId) {
    return endpoints.findAllByOrganizationId(organizationId).stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<WebhookEndpoint> findByIdAndOrganizationId(
      final UUID id, final UUID organizationId) {
    return endpoints.findByIdAndOrganizationId(id, organizationId).map(this::toDomain);
  }

  @Override
  public long countByOrganizationId(final UUID organizationId) {
    return endpoints.countByOrganizationId(organizationId);
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): newest-first, id as a tiebreaker — same reasoning
  // organization-module's own JpaOrganizationRepository#findKeysetPageOwnedBy already documents.
  @Override
  public KeysetPage<WebhookEndpoint> findKeysetPageByOrganizationId(
      final UUID organizationId, final KeysetPageRequest pageRequest) {
    final org.springframework.data.domain.PageRequest limit =
        org.springframework.data.domain.PageRequest.of(0, pageRequest.size() + 1);
    if (pageRequest.after() != null) {
      final KeysetCursor cursor = pageRequest.after();
      return SpringDataKeysetPageMapper.forward(
          endpoints.findPageByOrganizationIdAfter(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          true,
          this::toDomain,
          this::cursorOf);
    }
    if (pageRequest.before() != null) {
      final KeysetCursor cursor = pageRequest.before();
      return SpringDataKeysetPageMapper.backward(
          endpoints.findPageByOrganizationIdBefore(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          this::toDomain,
          this::cursorOf);
    }
    return SpringDataKeysetPageMapper.forward(
        endpoints.findFirstPageByOrganizationId(organizationId, limit),
        pageRequest.size(),
        false,
        this::toDomain,
        this::cursorOf);
  }

  private KeysetCursor cursorOf(final WebhookEndpointEntity entity) {
    return new KeysetCursor(entity.getCreatedAt(), entity.getId());
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

  // Same reasoning as insert()'s own @Transactional above: a Spring Data derived delete query
  // needs a real, currently-open transaction on this thread — the real production call path
  // already has one (DeleteOrganizationService.handle is @Transactional), but this method must
  // not depend on every future caller remembering that.
  @Override
  @Transactional
  public void deleteAllByOrganizationId(final UUID organizationId) {
    endpoints.deleteAllByOrganizationId(organizationId);
  }

  // TD-ARCH-053. One statement, so the increment and the switch-off cannot interleave with another
  // delivery of the same endpoint finishing at the same moment: Postgres re-reads the row under its
  // row lock, and exactly one call sees the count cross the threshold. `AND active` makes a call
  // for
  // an endpoint already switched off (by a person or by the call before) change nothing and report
  // false, so the caller logs the switch-off once.
  @Override
  public boolean recordExhaustedDelivery(
      final UUID endpointId, final int threshold, final Instant now) {
    final List<Boolean> switchedOff =
        jdbcTemplate.query(
            """
            UPDATE webhook_endpoints
               SET consecutive_failures = consecutive_failures + 1,
                   active = consecutive_failures + 1 < CAST(? AS integer),
                   auto_disabled_at = CASE WHEN consecutive_failures + 1 >= CAST(? AS integer)
                                        THEN CAST(? AS timestamptz) END
             WHERE id = ? AND active
            RETURNING NOT active
            """,
            (resultSet, _) -> resultSet.getBoolean(1),
            threshold,
            threshold,
            Timestamp.from(now),
            endpointId);
    return !switchedOff.isEmpty() && switchedOff.getFirst();
  }

  // TD-ARCH-053: a no-op unless the count is above zero, so the common case (a healthy endpoint)
  // never writes; the caller also skips the call when the endpoint it loaded shows zero.
  @Override
  public void resetConsecutiveFailures(final UUID endpointId) {
    jdbcTemplate.update(
        "UPDATE webhook_endpoints SET consecutive_failures = 0"
            + " WHERE id = ? AND consecutive_failures > 0",
        endpointId);
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
        entity.getCreatedAt(),
        entity.getConsecutiveFailures(),
        entity.getAutoDisabledAt());
  }

  private List<String> readJsonArray(final String json) {
    return Arrays.asList(objectMapper.readValue(json, String[].class));
  }
}
