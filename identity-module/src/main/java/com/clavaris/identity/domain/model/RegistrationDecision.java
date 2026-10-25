package com.clavaris.identity.domain.model;

import java.time.Instant;

/**
 * TD-FUT-019 (gated self-registration): when and why a {@code PENDING_APPROVAL} account was
 * decided. {@link #NONE} for every account that was never gated, which is the overwhelming
 * majority. {@code rejectionReason} is only ever set alongside a rejection and may be {@code null}
 * (the consuming application's callback is not guaranteed to supply one).
 */
public record RegistrationDecision(Instant decidedAt, String rejectionReason) {

  public static final RegistrationDecision NONE = new RegistrationDecision(null, null);

  /* default */ static RegistrationDecision approvedAt(final Instant now) {
    return new RegistrationDecision(now, null);
  }

  /* default */ static RegistrationDecision rejectedAt(final Instant now, final String reason) {
    return new RegistrationDecision(now, reason);
  }
}
