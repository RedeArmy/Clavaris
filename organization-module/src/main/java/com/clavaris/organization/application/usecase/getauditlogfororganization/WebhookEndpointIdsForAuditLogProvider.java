package com.clavaris.organization.application.usecase.getauditlogfororganization;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code app}, bridging to webhook-module's own {@code
 * ListWebhookEndpointsForOrganizationUseCase}. Same rationale as {@link
 * OAuthClientIdsForAuditLogProvider} — a separate interface, not a shared one, since this module
 * cannot depend on webhook-module directly.
 *
 * <p>Returns each {@code WebhookEndpoint}'s own domain {@code id()} — the exact value {@code
 * webhook_endpoint.registered}/{@code .activated}/{@code .deactivated}/{@code .secret_rotated}
 * already use as their own {@code audit_events.target_id}. {@code webhook_delivery.replayed} events
 * are deliberately out of scope for this pass — see {@code GetAuditLogForOrganizationService}'s own
 * Javadoc for why.
 */
@FunctionalInterface
public interface WebhookEndpointIdsForAuditLogProvider {

  List<String> webhookEndpointIds(UUID organizationId);
}
