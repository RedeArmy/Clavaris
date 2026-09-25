package com.clavaris.webhook.application.usecase.deletewebhookendpoint;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * Live UX request, 2026-09-25: the first single-entity (not cascade-from-Organization-delete) hard
 * delete for a {@code WebhookEndpoint} — same shape {@code client-registry-module}'s own {@code
 * DeleteOAuthClientService} already establishes: the endpoint must already be inactive ({@link
 * WebhookEndpointActiveException}), and the row's own {@code webhook_deliveries} history is removed
 * automatically at the database level ({@code ON DELETE CASCADE} — this table's own migration), not
 * walked and deleted here by hand.
 */
public class DeleteWebhookEndpointService implements DeleteWebhookEndpointUseCase {

  private final WebhookEndpointRepository endpoints;
  private final AuditEventRecorder auditEvents;

  public DeleteWebhookEndpointService(
      final WebhookEndpointRepository endpoints, final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final DeleteWebhookEndpointCommand command) {
    final WebhookEndpoint existing =
        endpoints
            .findById(command.endpointId())
            .orElseThrow(() -> new WebhookEndpointNotFoundException(command.endpointId()));

    if (existing.active()) {
      throw new WebhookEndpointActiveException(command.endpointId());
    }

    endpoints.delete(existing);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.deleted",
        "Organization",
        existing.organizationId().toString(),
        "deletedEndpointId=" + command.endpointId());
  }
}
