package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.time.Instant;
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

  /**
   * TD-FUT-032/SDE-III review, 2026-09-13: {@code DeleteOrganizationService}'s own cross-module
   * erasure call (via {@code OrganizationWebhookDataEraser}) — same rationale {@link
   * com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository#deleteAllByOrganizationId}
   * already documents. {@code organizationId} is denormalized onto this table directly (see {@code
   * WebhookDelivery}'s own Javadoc), so this doesn't need to go through {@code endpointId} at all.
   */
  void deleteAllByOrganizationId(UUID organizationId);

  /**
   * Live UX request, 2026-09-25 (Clerk-parity org-wide Logs tab): every delivery across every one
   * of an Organization's own endpoints, newest first — {@code listwebhookdeliveriesforendpoint}'s
   * own {@link #findAllByEndpointId} sibling, scoped one level up. Same keyset shape {@code
   * registerwebhookendpoint.WebhookEndpointRepository#findKeysetPageByOrganizationId} already
   * establishes.
   */
  KeysetPage<WebhookDelivery> findKeysetPageByOrganizationId(
      UUID organizationId, KeysetPageRequest pageRequest);

  /**
   * Live UX request, 2026-09-25 (Clerk-parity Activity tab): every delivery across every one of an
   * Organization's own endpoints with at least one attempt inside the window — {@code
   * getwebhookdeliveryactivityfororganization.GetWebhookDeliveryActivityForOrganizationService}'s
   * own source data, bucketed into hourly success/failure counts in Java, not SQL {@code GROUP BY}
   * — see {@code SpringDataWebhookDeliveryJpaRepository}'s own Javadoc for why that's safe here (a
   * short, bounded recent window, never the whole table).
   */
  List<WebhookDelivery> findAllByOrganizationIdWithAttemptSince(UUID organizationId, Instant since);
}
