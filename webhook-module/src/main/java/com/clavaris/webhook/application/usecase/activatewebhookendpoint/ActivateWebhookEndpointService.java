package com.clavaris.webhook.application.usecase.activatewebhookendpoint;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

/** Reverses {@code DeactivateWebhookEndpointService} — see its own Javadoc. */
public class ActivateWebhookEndpointService implements ActivateWebhookEndpointUseCase {

  private final WebhookEndpointRepository endpoints;
  private final AuditEventRecorder auditEvents;

  public ActivateWebhookEndpointService(
      final WebhookEndpointRepository endpoints, final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.auditEvents = auditEvents;
  }

  @Override
  public WebhookEndpoint handle(final ActivateWebhookEndpointCommand command) {
    final WebhookEndpoint existing =
        endpoints
            .findById(command.endpointId())
            .orElseThrow(() -> new WebhookEndpointNotFoundException(command.endpointId()));

    final WebhookEndpoint activated = existing.activate();
    endpoints.save(activated);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.activated",
        "WebhookEndpoint",
        activated.id().toString(),
        null);
    return activated;
  }
}
