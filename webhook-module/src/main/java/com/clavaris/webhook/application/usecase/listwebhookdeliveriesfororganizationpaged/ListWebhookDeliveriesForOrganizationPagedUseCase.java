package com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.webhook.domain.model.WebhookDelivery;

/**
 * Live UX request, 2026-09-25 (Clerk-parity org-wide Logs tab): the dashboard's own cross-endpoint
 * delivery listing — {@code listwebhookdeliveriesforendpoint}'s own unbounded, single-endpoint
 * sibling exists for a different page (that endpoint's own Deliveries tab); this one spans every
 * endpoint an Organization owns, same "paginated sibling exists only for the list page's own
 * display" shape {@code listwebhookendpointsfororganizationpaged} already establishes.
 */
@FunctionalInterface
public interface ListWebhookDeliveriesForOrganizationPagedUseCase {

  KeysetPage<WebhookDelivery> handle(ListWebhookDeliveriesForOrganizationPagedQuery query);
}
