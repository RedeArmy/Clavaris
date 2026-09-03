package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger LOG = LoggerFactory.getLogger(DispatchOutboxEventsService.class);

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

  // PMD.GuardLogStatement false positives — the values logged are cheap in-memory accessors, same
  // rationale as every other logging call site in this codebase (e.g.
  // DeliverPendingWebhooksService's
  // own identical suppression).
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void dispatchPendingEvents() {
    final List<OutboxEvent> claimed = outboxEvents.claimUnpublishedBatch(batchSizePerSource);
    if (claimed.isEmpty()) {
      return;
    }
    LOG.info("event=webhook_dispatch_batch_claimed claimedCount={}", claimed.size());
    for (final OutboxEvent event : claimed) {
      final List<WebhookEndpoint> matchingEndpoints =
          endpoints.findActiveByOrganizationIdAndEventType(
              event.organizationId(), event.eventType());
      for (final WebhookEndpoint endpoint : matchingEndpoints) {
        deliveries.save(
            WebhookDelivery.schedule(
                endpoint.id(),
                event.organizationId(),
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.payload(),
                event.traceId()));
      }
      // Zero matches is the expected, common case for most events (no consumer has subscribed to
      // this event type yet) — logged at debug, not warn, so it stays visible for tracing an
      // individual event end to end without flooding production logs on every scheduler tick.
      // traceId included so a request's own log lines (app tier) and this dispatch line can be
      // correlated even when the dispatcher tick runs long after the original request returned.
      LOG.debug(
          "event=webhook_dispatch_fanned_out outboxEventId={} organizationId={} eventType={}"
              + " matchedEndpointCount={} traceId={}",
          event.id(),
          event.organizationId(),
          event.eventType(),
          matchingEndpoints.size(),
          event.traceId());
      outboxEvents.markPublished(event);
    }
  }
}
