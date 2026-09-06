package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

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
}
