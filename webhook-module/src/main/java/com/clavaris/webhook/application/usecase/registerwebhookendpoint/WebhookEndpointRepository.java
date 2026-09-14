package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWebhookEndpointRepository}. Parked under {@code
 * registerwebhookendpoint} because that's this module's first use case, not because every method
 * here is scoped to it — {@code listwebhookendpointsfororganization}, {@code
 * rotatewebhookendpointsecret}, {@code deactivatewebhookendpoint}, and the dispatcher itself are
 * the other consumers, same precedent organization-module's own {@code WorkspaceRepository} already
 * establishes.
 */
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

  List<WebhookEndpoint> findAllByOrganizationId(UUID organizationId);

  /**
   * TD-PERF-020 (keyset revision, 2026-09-14): the dashboard's own paginated sibling of {@link
   * #findAllByOrganizationId} — used only by {@code
   * ListWebhookEndpointsForOrganizationPagedService}'s own display query. {@link
   * #findAllByOrganizationId} itself stays untouched — {@code PlatformWebhookEndpointController}'s
   * own anti-enumeration ownership check ({@code
   * WebhookDashboardControllerSupport#requireEndpointBelongsToOrganization}), {@code
   * PlatformWebhookDeliveryController}, and the audit-log id provider all genuinely need the full,
   * unbounded list.
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
