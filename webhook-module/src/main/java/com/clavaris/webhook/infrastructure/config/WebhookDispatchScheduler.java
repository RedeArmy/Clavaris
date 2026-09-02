package com.clavaris.webhook.infrastructure.config;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.DeliverPendingWebhooksUseCase;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.DispatchOutboxEventsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-0007 §1/§2: the two dispatcher phases, each its own independently-scheduled tick — fan-out
 * (Phase A, pure DB work) runs more often than delivery (Phase B, real network I/O against
 * arbitrary consumer endpoints), so a slow batch of deliveries never delays new events from being
 * observed. {@code @EnableScheduling} is already global ({@code ClavarisApplication}'s own Javadoc)
 * — this class only owns the bean/schedule wiring specific to this module's two use cases.
 *
 * <p>Both methods are individually safe to run concurrently across more than one instance of this
 * process (ADR-0007 §1's own NFR concurrency note) — {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * throughout means two instances racing the same tick simply split the work, never double-process
 * the same row.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class WebhookDispatchScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookDispatchScheduler.class);

  private final DispatchOutboxEventsUseCase dispatchOutboxEvents;
  private final DeliverPendingWebhooksUseCase deliverPendingWebhooks;

  /* package */ WebhookDispatchScheduler(
      final DispatchOutboxEventsUseCase dispatchOutboxEvents,
      final DeliverPendingWebhooksUseCase deliverPendingWebhooks) {
    this.dispatchOutboxEvents = dispatchOutboxEvents;
    this.deliverPendingWebhooks = deliverPendingWebhooks;
  }

  @Scheduled(
      fixedDelayString = "${clavaris.webhook.dispatch-fixed-delay-ms:5000}",
      initialDelayString = "${clavaris.webhook.dispatch-initial-delay-ms:5000}")
  // A single bad tick (a transient DB blip) must never kill the scheduled-task thread for every
  // future tick — same "isolated best-effort write" posture BestEffortEventPublisher's own
  // Javadoc establishes for a comparable narrow exception; this deliberately needs to catch
  // anything the use case can throw, not one specific type.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  /* package */ void dispatchTick() {
    try {
      dispatchOutboxEvents.dispatchPendingEvents();
    } catch (final RuntimeException e) {
      LOG.error("event=webhook_dispatch_tick_failed", e);
    }
  }

  @Scheduled(
      fixedDelayString = "${clavaris.webhook.delivery-fixed-delay-ms:5000}",
      initialDelayString = "${clavaris.webhook.delivery-initial-delay-ms:10000}")
  // Same "must never kill the scheduled-task thread" rationale as dispatchTick's own identical
  // suppression above.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  /* package */ void deliveryTick() {
    try {
      deliverPendingWebhooks.deliverDueDeliveries();
    } catch (final RuntimeException e) {
      LOG.error("event=webhook_delivery_tick_failed", e);
    }
  }
}
