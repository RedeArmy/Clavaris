package com.clavaris.webhook.domain.model;

/**
 * ADR-0007 §2: a delivery's own lifecycle, independent of the source {@code event_outbox} row's
 * {@code published_at} (which only means "fanned out to zero-or-more deliveries", not "delivered").
 */
public enum WebhookDeliveryStatus {

  /**
   * Not yet attempted, or leased by a dispatcher instance mid-attempt (see {@code
   * DeliverPendingWebhooksService}'s own claim-with-lease Javadoc).
   */
  PENDING,

  /** At least one attempt failed; {@code nextAttemptAt} says when the next retry is due. */
  FAILED,

  /** A 2xx response was received — terminal, never retried again. */
  SUCCEEDED,

  /** Every retry attempt was used up without success — terminal, visible for manual replay. */
  EXHAUSTED;

  /**
   * BR-WEBHOOK-03: only a delivery in one of these terminal states may be manually replayed — see
   * {@code ReplayWebhookDeliveryService}'s own Javadoc for why (an idempotency guard against racing
   * the ordinary retry engine, which still owns any {@code PENDING}/not-yet-due {@code FAILED}
   * row). Single source of truth for this check — both the service and the dashboard's own per-row
   * "Replay" button visibility read it from here rather than each keeping their own copy.
   */
  public boolean isReplayable() {
    return this == SUCCEEDED || this == EXHAUSTED;
  }
}
