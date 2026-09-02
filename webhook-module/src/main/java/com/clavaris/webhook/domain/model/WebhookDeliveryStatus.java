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
  EXHAUSTED
}
