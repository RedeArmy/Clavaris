package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; maps between {@code domain.model.WebhookDelivery} and {@link
 * WebhookDeliveryEntity}.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable"})
@Repository
class JpaWebhookDeliveryRepository implements WebhookDeliveryRepository {

  private final SpringDataWebhookDeliveryJpaRepository deliveries;
  private final Duration claimLeaseDuration;

  // claimLeaseDuration: how long a claimed-but-not-yet-attempted row stays ineligible for a second
  // claim — see WebhookDelivery.lease's own Javadoc. Comfortably wider than
  // JdkHttpWebhookSender's own request timeout so a delivery genuinely in flight is never re-
  // claimed by a concurrent tick.
  /* package */ JpaWebhookDeliveryRepository(
      final SpringDataWebhookDeliveryJpaRepository deliveries,
      @Value("${clavaris.webhook.delivery-claim-lease:PT5M}") final Duration claimLeaseDuration) {
    this.deliveries = deliveries;
    this.claimLeaseDuration = claimLeaseDuration;
  }

  @Override
  public void save(final WebhookDelivery delivery) {
    deliveries.save(
        new WebhookDeliveryEntity(
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
            delivery.createdAt()));
  }

  @Override
  public Optional<WebhookDelivery> findById(final UUID id) {
    return deliveries.findById(id).map(this::toDomain);
  }

  @Override
  public List<WebhookDelivery> findAllByEndpointId(final UUID endpointId, final int limit) {
    return deliveries.findAllByEndpointIdOrderByCreatedAtDesc(endpointId).stream()
        .limit(limit)
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
