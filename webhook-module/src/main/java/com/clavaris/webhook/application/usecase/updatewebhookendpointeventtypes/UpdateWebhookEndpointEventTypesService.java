package com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * Live UX request, 2026-09-25: same editable-after-registration treatment as the endpoint's URL —
 * {@link WebhookEndpoint#updateEventTypes} itself enforces the non-empty invariant (BR-WEBHOOK-06),
 * including the {@link WebhookEndpoint#ALL_EVENTS_WILDCARD} sentinel this dashboard's own "All
 * Events" checkbox may submit here exactly as it does at registration.
 */
public class UpdateWebhookEndpointEventTypesService
    implements UpdateWebhookEndpointEventTypesUseCase {

  private final WebhookEndpointRepository endpoints;
  private final AuditEventRecorder auditEvents;

  public UpdateWebhookEndpointEventTypesService(
      final WebhookEndpointRepository endpoints, final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.auditEvents = auditEvents;
  }

  @Override
  public WebhookEndpoint handle(final UpdateWebhookEndpointEventTypesCommand command) {
    final WebhookEndpoint existing =
        endpoints
            .findById(command.endpointId())
            .orElseThrow(() -> new WebhookEndpointNotFoundException(command.endpointId()));

    final WebhookEndpoint updated = existing.updateEventTypes(command.subscribedEventTypes());
    endpoints.save(updated);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.event_types_updated",
        "WebhookEndpoint",
        updated.id().toString(),
        null);
    return updated;
  }
}
