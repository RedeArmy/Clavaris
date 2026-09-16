package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import java.util.UUID;

/**
 * BR-WEBHOOK-08 (SDE-III review, 2026-09-15): thrown when an Organization has already registered
 * {@link RegisterWebhookEndpointService#MAX_ENDPOINTS_PER_ORGANIZATION} endpoints. {@code
 * DispatchOutboxEventsService} fans out one {@code WebhookDelivery} per active endpoint subscribed
 * to an event type, per event, inside one shared, single-threaded dispatcher — an unbounded
 * registration count for one Organization is a cost multiplier every other tenant's own delivery
 * latency pays for too (the shared 99.5% SLA), not just that Organization's own throughput.
 */
public final class WebhookEndpointLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WebhookEndpointLimitExceededException(final UUID organizationId, final int limit) {
    super(
        "Organization "
            + organizationId
            + " has already registered the maximum of "
            + limit
            + " webhook endpoints");
  }
}
