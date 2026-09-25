package com.clavaris.webhook.application.usecase.updatewebhookendpointdescription;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * Live UX request, 2026-09-25: same editable-after-registration treatment as the endpoint's URL.
 */
public class UpdateWebhookEndpointDescriptionService
    implements UpdateWebhookEndpointDescriptionUseCase {

  private final WebhookEndpointRepository endpoints;
  private final AuditEventRecorder auditEvents;

  public UpdateWebhookEndpointDescriptionService(
      final WebhookEndpointRepository endpoints, final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.auditEvents = auditEvents;
  }

  @Override
  public WebhookEndpoint handle(final UpdateWebhookEndpointDescriptionCommand command) {
    final WebhookEndpoint existing =
        endpoints
            .findById(command.endpointId())
            .orElseThrow(() -> new WebhookEndpointNotFoundException(command.endpointId()));

    final WebhookEndpoint updated = existing.updateDescription(command.description());
    endpoints.save(updated);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.description_updated",
        "WebhookEndpoint",
        updated.id().toString(),
        null);
    return updated;
  }
}
