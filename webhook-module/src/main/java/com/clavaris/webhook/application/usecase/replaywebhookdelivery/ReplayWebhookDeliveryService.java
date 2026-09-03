package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ReplayWebhookDeliveryUseCase} — BR-WEBHOOK-03: an operator manually
 * re-triggers one past delivery (e.g. the consumer's endpoint was down for the whole retry window
 * and has since recovered). Marks the row {@code PENDING} and due immediately; the very next {@code
 * DeliverPendingWebhooksService} tick picks it up the same way any ordinary retry would — no
 * separate "replay" code path in the delivery engine itself.
 *
 * <p><b>Idempotency guard, not just a courtesy check:</b> only a delivery already in a terminal
 * state ({@link WebhookDeliveryStatus#SUCCEEDED}/{@link WebhookDeliveryStatus#EXHAUSTED}) may be
 * replayed. {@code WebhookDelivery} carries no optimistic-locking version column — a replay of a
 * row the ordinary retry engine still owns ({@code PENDING}, or {@code FAILED} with its own {@code
 * nextAttemptAt} still pending) would race {@code DeliverPendingWebhooksService}'s own
 * claim-then-lease cycle with nothing to arbitrate the concurrent write, and could fire a second,
 * genuinely duplicate HTTP delivery to the consumer for the exact same event. Real bug found and
 * closed by this same review, not hypothetical — see the technical-debt register's own entry for
 * the live scenario this guard was written against.
 *
 * <p><b>Endpoint-ownership guard (SDE-III review, 2026-09-03 — real bug, found and closed):</b> the
 * web adapter's own URL ({@code .../webhook-endpoints/{endpointId}/deliveries/{deliveryId}:replay})
 * names {@code endpointId} as the resource this acts on, but nothing previously checked the loaded
 * {@code WebhookDelivery} actually belongs to it — a {@code deliveryId} for a different endpoint
 * (or a different Organization entirely) still replayed, silently ignoring the path segment. Every
 * sibling delivery-scoped use case in this module ({@code ListWebhookDeliveriesForEndpointService})
 * already verifies this; this was the one that didn't. A mismatch is treated identically to "no
 * such delivery" ({@link WebhookDeliveryNotFoundException}) — never a differentiated response that
 * would let a caller distinguish "wrong endpoint" from "doesn't exist at all."
 *
 * <p><b>SDE-III review, 2026-09-03 — real bug found and closed:</b> {@code handle()} previously
 * wasn't {@code @Transactional} — the delivery reset ({@code deliveries.save}) and the audit-trail
 * write ({@code auditEvents.write}) were two independent, non-atomic commits. If the audit write
 * failed after the save succeeded, the delivery would still replay on the next scheduler tick with
 * no audit record of who triggered it — undermining the exact traceability this operation exists to
 * provide, and a real gap given {@code AuditEventRecorder}'s own JPA implementation writes to the
 * same Postgres database as {@code webhook_deliveries}, the same "one transaction, one commit
 * boundary" discipline every other audited mutation in this codebase (e.g. {@code
 * SuspendAccountService}) already follows.
 */
@SuppressWarnings("PMD.LongVariable")
public class ReplayWebhookDeliveryService implements ReplayWebhookDeliveryUseCase {

  private static final Set<WebhookDeliveryStatus> REPLAYABLE_STATUSES =
      EnumSet.of(WebhookDeliveryStatus.SUCCEEDED, WebhookDeliveryStatus.EXHAUSTED);

  private final WebhookDeliveryRepository deliveries;
  private final AuditEventRecorder auditEvents;

  public ReplayWebhookDeliveryService(
      final WebhookDeliveryRepository deliveries, final AuditEventRecorder auditEvents) {
    this.deliveries = deliveries;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public WebhookDelivery handle(final ReplayWebhookDeliveryCommand command) {
    final WebhookDelivery existing =
        deliveries
            .findById(command.deliveryId())
            .orElseThrow(() -> new WebhookDeliveryNotFoundException(command.deliveryId()));

    // See this class's own Javadoc ("Endpoint-ownership guard") — a mismatch is a 404, not a
    // silent replay against the wrong endpoint.
    if (!existing.endpointId().equals(command.endpointId())) {
      throw new WebhookDeliveryNotFoundException(command.deliveryId());
    }

    if (!REPLAYABLE_STATUSES.contains(existing.status())) {
      throw new WebhookDeliveryNotReplayableException(existing.id(), existing.status());
    }

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
