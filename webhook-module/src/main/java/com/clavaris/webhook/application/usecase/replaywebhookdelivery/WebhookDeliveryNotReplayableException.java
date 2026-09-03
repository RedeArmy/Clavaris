package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.util.UUID;

/**
 * BR-WEBHOOK-03 scopes replay to a *past* delivery — one already in a terminal state ({@link
 * WebhookDeliveryStatus#SUCCEEDED}/{@link WebhookDeliveryStatus#EXHAUSTED}). A {@code PENDING} or
 * {@code FAILED}-but-not-yet-due row is still owned by the ordinary retry engine: replaying it
 * anyway would race {@link
 * com.clavaris.webhook.application.usecase.deliverpendingwebhooks.DeliverPendingWebhooksService}'s
 * own claim-then-lease cycle (no optimistic locking exists on {@code WebhookDelivery} to arbitrate
 * a concurrent write), risking a genuine duplicate HTTP delivery to the consumer's own endpoint —
 * exactly the class of bug this guard exists to make structurally impossible rather than rely on
 * the consumer's own idempotency handling to paper over.
 */
public final class WebhookDeliveryNotReplayableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  @SuppressWarnings("PMD.ShortVariable")
  public WebhookDeliveryNotReplayableException(final UUID id, final WebhookDeliveryStatus status) {
    super(
        "WebhookDelivery "
            + id
            + " is not replayable in status "
            + status
            + " — only SUCCEEDED/EXHAUSTED deliveries can be replayed");
  }
}
