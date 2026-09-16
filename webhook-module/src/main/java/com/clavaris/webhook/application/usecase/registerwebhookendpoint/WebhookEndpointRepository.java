package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWebhookEndpointRepository}. Parked under {@code
 * registerwebhookendpoint} because that's this module's first use case, not because every method
 * here is scoped to it — {@code listwebhookendpointsfororganization}, {@code
 * getwebhookendpointfororganization}, {@code rotatewebhookendpointsecret}, {@code
 * deactivatewebhookendpoint}, and the dispatcher itself are the other consumers, same precedent
 * organization-module's own {@code WorkspaceRepository} already establishes.
 */
// PMD.TooManyMethods: every method here backs a real, distinct use case this module genuinely
// needs — same "one port, several use cases" shape JpaWebhookEndpointRepository's own identical
// suppression documents for this exact interface's implementation.
@SuppressWarnings("PMD.TooManyMethods")
public interface WebhookEndpointRepository {

  void save(WebhookEndpoint endpoint);

  /**
   * TD-PERF-019: same write as {@link #save}, for the one call site that knows for a fact this
   * {@code WebhookEndpoint} has never been persisted before — {@code
   * RegisterWebhookEndpointService}, which always constructs via {@code
   * WebhookEndpoint.register(...)}. Every mutating call site below registration ({@code
   * rotatewebhookendpointsecret}, {@code (de)activatewebhookendpoint}) first loads via {@link
   * #findById} and must keep calling {@link #save}. Same rationale {@code
   * AccountRepository#insert}'s own identical addition documents.
   */
  void insert(WebhookEndpoint endpoint);

  /**
   * Every mutating use case below registration ({@code rotatewebhookendpointsecret}, {@code
   * (de)activatewebhookendpoint}) addresses an endpoint by this id alone — the platform-tier caller
   * that reaches this whole admin API surface is already trusted across every Organization
   * (BR-PLATFORM-02), so unlike a tenant-facing endpoint there is no cross-Organization boundary to
   * additionally check here; a caller passing a different Organization's id anywhere in the URL
   * couldn't reach anything a valid platform token doesn't already reach via the org-scoped
   * endpoints too.
   */
  @SuppressWarnings("PMD.ShortVariable")
  Optional<WebhookEndpoint> findById(UUID id);

  /**
   * TD-PERF-025 (SDE-III review, 2026-09-15): {@code
   * com.clavaris.webhook.application.usecase.deliverpendingwebhooks.DeliverPendingWebhooksService}'s
   * own batch-fetch — one query for every distinct {@code endpointId} in a claimed delivery batch
   * (up to {@code batchSize}, 50 by default), instead of {@link #findById} once per claimed
   * delivery. Same "fetch once per distinct key in the batch, not once per row" fix {@link
   * com.clavaris.webhook.application.usecase.dispatchoutboxevents.DispatchOutboxEventsService}'s
   * own {@code findActiveByOrganizationId} memoization (TD-PERF-005) already applies one layer up
   * (by Organization, for fan-out); this is the delivery-side sibling of that same problem, keyed
   * by endpoint instead. Duplicate ids in {@code ids} cost nothing extra — {@code
   * JpaRepository#findAllById} already de-duplicates its own {@code WHERE id IN (...)} query.
   */
  List<WebhookEndpoint> findAllByIds(Collection<UUID> ids);

  List<WebhookEndpoint> findAllByOrganizationId(UUID organizationId);

  /**
   * SDE-III review, 2026-09-16 — TD-PERF-026: a single-row, indexed lookup (both columns are part
   * of the entity's own primary-key/foreign-key pair) for exactly the "does this endpoint belong to
   * this Organization" question every dashboard controller action needs before mutating or reading
   * anything keyed by {@code endpointId} alone. Was previously answered by fetching every endpoint
   * for the Organization via {@link #findAllByOrganizationId} and scanning it in memory for a match
   * ({@code WebhookDashboardControllerSupport#requireEndpointBelongsToOrganization}'s own former
   * body) — O(n) work, repeated on every admin click (deactivate/activate/rotate-secret/list-
   * deliveries/replay), for a question a single {@code WHERE id = ? AND organization_id = ?}
   * answers directly. Same class of fix {@code client-registry-module}'s own {@code
   * DashboardControllerSupport#requireClientIdBelongsToOrganization} removal already established
   * for an identical shape of workaround (SDE-III review, 2026-09-15).
   */
  @SuppressWarnings("PMD.ShortVariable")
  Optional<WebhookEndpoint> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * BR-WEBHOOK-08 (SDE-III review, 2026-09-15): backs {@link RegisterWebhookEndpointService}'s own
   * per-Organization registration cap — counts every endpoint ever registered for this
   * Organization, active or deactivated, never just the active ones. A count that only counted
   * active endpoints could be trivially bypassed: register up to the cap, deactivate them all,
   * register a fresh batch, then reactivate everything via {@code ActivateWebhookEndpointService} —
   * none of which re-checks this cap. There is no way to delete a single {@code WebhookEndpoint}
   * (only deactivate — {@code deleteAllByOrganizationId} is Organization-deletion-only), so this
   * count only ever grows for a live Organization, making it a stable, un-gameable bound.
   */
  long countByOrganizationId(UUID organizationId);

  /**
   * TD-PERF-020 (keyset revision, 2026-09-14): the dashboard's own paginated sibling of {@link
   * #findAllByOrganizationId} — used only by {@code
   * ListWebhookEndpointsForOrganizationPagedService}'s own display query. {@link
   * #findAllByOrganizationId} itself stays untouched — {@code ListWebhookEndpointsController} (the
   * REST admin API's own list endpoint) and the audit-log id provider genuinely need the full,
   * unbounded list. TD-PERF-026 (2026-09-16) closed the one caller that did not: the dashboard's
   * own anti-enumeration ownership check now uses {@link #findByIdAndOrganizationId} instead — see
   * that method's own Javadoc.
   */
  KeysetPage<WebhookEndpoint> findKeysetPageByOrganizationId(
      UUID organizationId, KeysetPageRequest pageRequest);

  /**
   * ADR-0007 §1: active endpoints subscribed to this event, for a single (organization, event type)
   * pair.
   */
  List<WebhookEndpoint> findActiveByOrganizationIdAndEventType(
      UUID organizationId, String eventType);

  /**
   * TD-PERF-005: every active endpoint for this Organization, regardless of which event types it
   * subscribes to — {@link
   * com.clavaris.webhook.application.usecase.dispatchoutboxevents.DispatchOutboxEventsService}'s
   * own batch-by-organization fan-out calls this once per distinct Organization in a claimed outbox
   * batch and filters by {@code eventType} in memory itself (via {@link
   * WebhookEndpoint#subscribesTo}), instead of {@link #findActiveByOrganizationIdAndEventType} once
   * per claimed event — the same list is reused across every event from the same Organization in
   * one dispatch tick, rather than re-fetched from Postgres for each one.
   */
  List<WebhookEndpoint> findActiveByOrganizationId(UUID organizationId);

  /**
   * TD-FUT-032/SDE-III review, 2026-09-13: {@code DeleteOrganizationService}'s own cross-module
   * erasure call (via {@code OrganizationWebhookDataEraser}) — {@code organization_id} carries no
   * FK to {@code organizations} at all (a deliberate cross-module boundary, this table's own
   * migration comment), so without this method a deleted Organization's own endpoints would
   * silently survive as orphaned rows.
   */
  void deleteAllByOrganizationId(UUID organizationId);
}
