package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link DispatchOutboxEventsUseCase} — ADR-0007 §1's own fan-out step: for each
 * newly-claimed outbox row, one {@link WebhookDelivery} is scheduled per active {@link
 * WebhookEndpoint} subscribed to that event type in that Organization (zero if none are), then the
 * source row is marked published.
 *
 * <p>{@code @Transactional} on {@link #dispatchPendingEvents()} itself, deliberately — unlike
 * {@code DeliverPendingWebhooksService}'s own two-phase split (claim in one transaction, HTTP call
 * untransacted, outcome recorded in a second transaction), this whole method is pure DB work, no
 * network I/O: the {@code SELECT ... FOR UPDATE SKIP LOCKED} claim must stay locked across the
 * {@code WebhookDelivery} inserts and the final {@code markPublished} write, or a second concurrent
 * dispatcher tick could claim the same still-unpublished row before this one finishes fanning it
 * out.
 */
@SuppressWarnings("PMD.LongVariable")
public class DispatchOutboxEventsService implements DispatchOutboxEventsUseCase {

  private final OutboxEventReader outboxEvents;
  private final WebhookEndpointRepository endpoints;
  private final WebhookDeliveryRepository deliveries;
  private final int batchSizePerSource;

  public DispatchOutboxEventsService(
      final OutboxEventReader outboxEvents,
      final WebhookEndpointRepository endpoints,
      final WebhookDeliveryRepository deliveries,
      final int batchSizePerSource) {
    this.outboxEvents = outboxEvents;
    this.endpoints = endpoints;
    this.deliveries = deliveries;
    this.batchSizePerSource = batchSizePerSource;
  }

  @Override
  @Transactional
  public void dispatchPendingEvents() {
    for (final OutboxEvent event : outboxEvents.claimUnpublishedBatch(batchSizePerSource)) {
      for (final WebhookEndpoint endpoint :
          endpoints.findActiveByOrganizationIdAndEventType(
              event.organizationId(), event.eventType())) {
        deliveries.save(
            WebhookDelivery.schedule(
                endpoint.id(),
                event.organizationId(),
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.payload()));
      }
      outboxEvents.markPublished(event);
    }
  }
}
