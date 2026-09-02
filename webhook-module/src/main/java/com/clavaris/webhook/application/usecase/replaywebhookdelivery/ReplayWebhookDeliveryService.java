package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.time.Instant;

/**
 * Orchestration for {@link ReplayWebhookDeliveryUseCase} — BR-WEBHOOK-03: an operator manually
 * re-triggers one past delivery (e.g. the consumer's endpoint was down for the whole retry window
 * and has since recovered). Marks the row {@code PENDING} and due immediately; the very next {@code
 * DeliverPendingWebhooksService} tick picks it up the same way any ordinary retry would — no
 * separate "replay" code path in the delivery engine itself.
 */
public class ReplayWebhookDeliveryService implements ReplayWebhookDeliveryUseCase {

  private final WebhookDeliveryRepository deliveries;
  private final AuditEventRecorder auditEvents;

  public ReplayWebhookDeliveryService(
      final WebhookDeliveryRepository deliveries, final AuditEventRecorder auditEvents) {
    this.deliveries = deliveries;
    this.auditEvents = auditEvents;
  }

  @Override
  public WebhookDelivery handle(final ReplayWebhookDeliveryCommand command) {
    final WebhookDelivery existing =
        deliveries
            .findById(command.deliveryId())
            .orElseThrow(() -> new WebhookDeliveryNotFoundException(command.deliveryId()));

    final WebhookDelivery replayed = existing.resetForReplay(Instant.now());
    deliveries.save(replayed);
    auditEvents.write(
        command.actor(),
        "webhook_delivery.replayed",
        "WebhookDelivery",
        replayed.id().toString(),
        null);
    return replayed;
  }
}
