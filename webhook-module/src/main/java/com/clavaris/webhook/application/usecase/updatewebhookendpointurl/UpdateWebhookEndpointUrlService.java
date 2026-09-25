package com.clavaris.webhook.application.usecase.updatewebhookendpointurl;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookUrlSsrfGuard;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * Live UX request, 2026-09-25: the endpoint URL is editable after registration, unlike OAuth
 * Client's grant types/scopes/consent before that same live UX request pattern reversed them. A new
 * URL goes through the exact same two checks as registration — {@link WebhookEndpoint#updateUrl}'s
 * own {@code requireValidUrl} (BR-WEBHOOK-07, https-only) and this service's own {@link
 * WebhookUrlSsrfGuard} call (TD-SEC-053) — an edited URL is exactly as capable of pointing at a
 * private/internal address as one typed at registration time, so it gets exactly the same defense,
 * not a weaker one because it arrives via a different form.
 */
public class UpdateWebhookEndpointUrlService implements UpdateWebhookEndpointUrlUseCase {

  private final WebhookEndpointRepository endpoints;
  private final WebhookUrlSsrfGuard ssrfGuard;
  private final AuditEventRecorder auditEvents;

  public UpdateWebhookEndpointUrlService(
      final WebhookEndpointRepository endpoints,
      final WebhookUrlSsrfGuard ssrfGuard,
      final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.ssrfGuard = ssrfGuard;
    this.auditEvents = auditEvents;
  }

  @Override
  public WebhookEndpoint handle(final UpdateWebhookEndpointUrlCommand command) {
    final WebhookEndpoint existing =
        endpoints
            .findById(command.endpointId())
            .orElseThrow(() -> new WebhookEndpointNotFoundException(command.endpointId()));

    // TD-SEC-053: before the domain-level well-formedness check below even runs — same ordering
    // RegisterWebhookEndpointService's own identical call already establishes.
    ssrfGuard.requireSafeToRegister(command.url());

    final WebhookEndpoint updated = existing.updateUrl(command.url());
    endpoints.save(updated);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.url_updated",
        "WebhookEndpoint",
        updated.id().toString(),
        null);
    return updated;
  }
}
