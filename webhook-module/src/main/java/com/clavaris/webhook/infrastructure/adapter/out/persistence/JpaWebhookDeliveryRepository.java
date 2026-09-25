package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.common.infrastructure.adapter.out.persistence.SpringDataKeysetPageMapper;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; maps between {@code domain.model.WebhookDelivery} and {@link
 * WebhookDeliveryEntity}.
 *
 * <p>TD-PERF-019: {@code insert} calls {@link EntityManager#persist} directly, not {@code
 * SpringDataWebhookDeliveryJpaRepository#save} — see {@code WebhookDeliveryRepository#insert}'s own
 * Javadoc for which call site that's safe for and why {@code save} itself is unchanged.
 *
 * <p>PMD.TooManyMethods (the Logs tab's own {@code findKeysetPageByOrganizationId}/{@code
 * findAllByOrganizationIdWithAttemptSince} pushed this past the default threshold): every method
 * here backs a real, distinct {@code WebhookDeliveryRepository} port method this module's use cases
 * actually need — same "one port, several use cases" shape {@code JpaWebhookEndpointRepository}'s
 * own identical suppression documents, not a design smell to split up.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable", "PMD.TooManyMethods"})
@Repository
class JpaWebhookDeliveryRepository implements WebhookDeliveryRepository {

  private final SpringDataWebhookDeliveryJpaRepository deliveries;
  private final Duration claimLeaseDuration;
  private final EntityManager entityManager;

  // claimLeaseDuration: how long a claimed-but-not-yet-attempted row stays ineligible for a second
  // claim — see WebhookDelivery.lease's own Javadoc. Comfortably wider than
  // JdkHttpWebhookSender's own request timeout so a delivery genuinely in flight is never re-
  // claimed by a concurrent tick.
  /* package */ JpaWebhookDeliveryRepository(
      final SpringDataWebhookDeliveryJpaRepository deliveries,
      @Value("${clavaris.webhook.delivery-claim-lease:PT5M}") final Duration claimLeaseDuration,
      final EntityManager entityManager) {
    this.deliveries = deliveries;
    this.claimLeaseDuration = claimLeaseDuration;
    this.entityManager = entityManager;
  }

  @Override
  public void save(final WebhookDelivery delivery) {
    deliveries.save(toEntity(delivery));
  }

  @Override
  @Transactional
  public void insert(final WebhookDelivery delivery) {
    entityManager.persist(toEntity(delivery));
  }

  private WebhookDeliveryEntity toEntity(final WebhookDelivery delivery) {
    return new WebhookDeliveryEntity(
        delivery.id(),
        delivery.endpointId(),
        delivery.organizationId(),
        delivery.outboxEventId(),
        delivery.aggregateType(),
        delivery.aggregateId(),
        delivery.eventType(),
        delivery.payload(),
        delivery.traceId(),
        delivery.status().name(),
        delivery.attemptCount(),
        delivery.nextAttemptAt(),
        delivery.lastAttemptAt(),
        delivery.lastResponseStatus(),
        delivery.lastError(),
        delivery.createdAt());
  }

  @Override
  public Optional<WebhookDelivery> findById(final UUID id) {
    return deliveries.findById(id).map(this::toDomain);
  }

  // TD-PERF-012: PageRequest.of(0, limit) pushes the LIMIT itself into the generated SQL — see
  // SpringDataWebhookDeliveryJpaRepository's own Javadoc for why this replaced an unbounded fetch
  // truncated in Java.
  @Override
  public List<WebhookDelivery> findAllByEndpointId(final UUID endpointId, final int limit) {
    return deliveries
        .findByEndpointIdOrderByCreatedAtDesc(endpointId, PageRequest.of(0, limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  // See DeliverPendingWebhooksService's own Javadoc for why claiming is its own short, dedicated
  // transaction — everything below commits and releases its row locks before this method returns,
  // well before any HTTP call the caller goes on to make.
  // PMD.OnlyOneReturn: the empty-batch early exit skips two DB round trips (lease + re-select)
  // that would otherwise run against an empty id list — a genuinely distinct case, not worth
  // forcing through a single-exit shape.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  @Transactional
  public List<WebhookDelivery> claimDueBatch(final int limit) {
    final List<UUID> dueIds = deliveries.selectDueIdsForUpdateSkipLocked(Instant.now(), limit);
    if (dueIds.isEmpty()) {
      return List.of();
    }
    deliveries.leaseByIds(dueIds, Instant.now().plus(claimLeaseDuration));
    return deliveries.findByIdIn(dueIds).stream().map(this::toDomain).toList();
  }

  // Same reasoning as insert()'s own @Transactional above: a Spring Data derived delete query
  // needs a real, currently-open transaction on this thread — the real production call path
  // already has one (DeleteOrganizationService.handle is @Transactional), but this method must
  // not depend on every future caller remembering that.
  @Override
  @Transactional
  public void deleteAllByOrganizationId(final UUID organizationId) {
    deliveries.deleteAllByOrganizationId(organizationId);
  }

  // Same "three @Query methods back one keyset page" shape as JpaWebhookEndpointRepository's own
  // identical findKeysetPageByOrganizationId.
  @Override
  @SuppressWarnings("PMD.OnlyOneReturn") // three real, distinct exits — first/after/before.
  public KeysetPage<WebhookDelivery> findKeysetPageByOrganizationId(
      final UUID organizationId, final KeysetPageRequest pageRequest) {
    final PageRequest limit = PageRequest.of(0, pageRequest.size() + 1);
    if (pageRequest.after() != null) {
      final KeysetCursor cursor = pageRequest.after();
      return SpringDataKeysetPageMapper.forward(
          deliveries.findPageByOrganizationIdAfter(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          true,
          this::toDomain,
          this::cursorOf);
    }
    if (pageRequest.before() != null) {
      final KeysetCursor cursor = pageRequest.before();
      return SpringDataKeysetPageMapper.backward(
          deliveries.findPageByOrganizationIdBefore(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          this::toDomain,
          this::cursorOf);
    }
    return SpringDataKeysetPageMapper.forward(
        deliveries.findFirstPageByOrganizationId(organizationId, limit),
        pageRequest.size(),
        false,
        this::toDomain,
        this::cursorOf);
  }

  private KeysetCursor cursorOf(final WebhookDeliveryEntity entity) {
    return new KeysetCursor(entity.getCreatedAt(), entity.getId());
  }

  @Override
  public List<WebhookDelivery> findAllByOrganizationIdWithAttemptSince(
      final UUID organizationId, final Instant since) {
    return deliveries
        .findAllByOrganizationIdAndLastAttemptAtGreaterThanEqual(organizationId, since)
        .stream()
        .map(this::toDomain)
        .toList();
  }

  private WebhookDelivery toDomain(final WebhookDeliveryEntity entity) {
    return WebhookDelivery.reconstitute(
        entity.getId(),
        entity.getEndpointId(),
        entity.getOrganizationId(),
        entity.getOutboxEventId(),
        entity.getAggregateType(),
        entity.getAggregateId(),
        entity.getEventType(),
        entity.getPayload(),
        entity.getTraceId(),
        WebhookDeliveryStatus.valueOf(entity.getStatus()),
        entity.getAttemptCount(),
        entity.getNextAttemptAt(),
        entity.getLastAttemptAt(),
        entity.getLastResponseStatus(),
        entity.getLastError(),
        entity.getCreatedAt());
  }
}
