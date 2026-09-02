package com.clavaris.webhook.application.usecase.deactivatewebhookendpoint;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * Reversible — no hard delete, same "reversible over permanent where the semantics allow it"
 * precedent identity-module's own {@code SuspendAccount}/{@code ReactivateAccount} pair already
 * establishes. A deactivated endpoint is simply excluded from the dispatcher's own fan-out lookup
 * ({@code WebhookEndpointRepository#findActiveByOrganizationIdAndEventType}) — no deliveries are
 * scheduled to it while inactive, but its own configuration/history is untouched and reactivation
 * ({@code ActivateWebhookEndpointService}) needs no re-registration.
 */
public class DeactivateWebhookEndpointService implements DeactivateWebhookEndpointUseCase {

  private final WebhookEndpointRepository endpoints;
  private final AuditEventRecorder auditEvents;

  public DeactivateWebhookEndpointService(
      final WebhookEndpointRepository endpoints, final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.auditEvents = auditEvents;
  }

  @Override
  public WebhookEndpoint handle(final DeactivateWebhookEndpointCommand command) {
    final WebhookEndpoint existing =
        endpoints
            .findById(command.endpointId())
            .orElseThrow(() -> new WebhookEndpointNotFoundException(command.endpointId()));

    final WebhookEndpoint deactivated = existing.deactivate();
    endpoints.save(deactivated);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.deactivated",
        "WebhookEndpoint",
        deactivated.id().toString(),
        null);
    return deactivated;
  }
}
