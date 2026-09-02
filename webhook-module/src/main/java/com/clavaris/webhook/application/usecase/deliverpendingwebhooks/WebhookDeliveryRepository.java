package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWebhookDeliveryRepository}. Parked under {@code
 * deliverpendingwebhooks} because that's this module's own primary consumer, not because every
 * method here is scoped to it — {@code dispatchoutboxevents} (creating new rows) and {@code
 * replaywebhookdelivery}/{@code listwebhookdeliveriesforendpoint} (reading them back) are the other
 * consumers, same "one port, several use cases" precedent {@code WebhookEndpointRepository} already
 * establishes in this module.
 */
public interface WebhookDeliveryRepository {

  void save(WebhookDelivery delivery);

  /**
   * Addressed by this id alone — see {@code WebhookEndpointRepository#findById}'s own Javadoc for
   * why no additional Organization-scoping check applies on this admin API surface.
   */
  @SuppressWarnings("PMD.ShortVariable")
  Optional<WebhookDelivery> findById(UUID id);

  List<WebhookDelivery> findAllByEndpointId(UUID endpointId, int limit);

  /**
   * Claims up to {@code limit} rows due for an attempt right now ({@code PENDING}, or {@code
   * FAILED} with {@code nextAttemptAt <= now}) via {@code SELECT ... FOR UPDATE SKIP LOCKED}, then
   * immediately leases each one ({@code WebhookDelivery#lease}) before returning — see {@code
   * DeliverPendingWebhooksService}'s own Javadoc for why the lease step happens inside this same
   * short claiming transaction rather than around the HTTP call that follows.
   */
  List<WebhookDelivery> claimDueBatch(int limit);
}
