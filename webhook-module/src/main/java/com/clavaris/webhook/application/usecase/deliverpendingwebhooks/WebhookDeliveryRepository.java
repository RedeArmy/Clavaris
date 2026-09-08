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
   * TD-PERF-019: same write as {@link #save}, for the one call site that knows for a fact this
   * {@code WebhookDelivery} has never been persisted before — {@code DispatchOutboxEventsService}'s
   * own fan-out loop, which always constructs via {@code WebhookDelivery.schedule(...)}. Every
   * other call site ({@code DeliverPendingWebhooksService}'s {@code recordSuccess}/{@code
   * recordFailure}, {@code ReplayWebhookDeliveryService}'s {@code resetForReplay}) first loads an
   * existing row (via {@link #claimDueBatch} or {@link #findById}) and must keep calling {@link
   * #save}. Same rationale {@code AccountRepository#insert}'s own identical addition documents.
   */
  void insert(WebhookDelivery delivery);

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
