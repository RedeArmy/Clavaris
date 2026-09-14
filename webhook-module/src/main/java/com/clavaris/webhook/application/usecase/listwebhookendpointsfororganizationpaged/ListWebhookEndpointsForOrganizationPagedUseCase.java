package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.webhook.domain.model.WebhookEndpoint;

/**
 * TD-PERF-020 (keyset revision, 2026-09-14): the dashboard's own paginated sibling of {@code
 * ListWebhookEndpointsForOrganizationUseCase} — that use case stays unbounded and remains the one
 * {@code PlatformWebhookEndpointController} uses for its own anti-enumeration ownership check
 * (deactivate/activate/rotate-secret must recognize an endpointId regardless of which page the
 * dashboard happens to be showing), the one {@code PlatformWebhookDeliveryController} uses to
 * resolve an endpoint, and the one the audit-log id provider fans out over; this one exists only
 * for the list page's own display.
 */
@FunctionalInterface
public interface ListWebhookEndpointsForOrganizationPagedUseCase {

  KeysetPage<WebhookEndpoint> handle(ListWebhookEndpointsForOrganizationPagedQuery query);
}
