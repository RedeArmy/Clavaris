package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * domain-model.md §6: {@code PasswordResetRequestedEvent} — "best-effort: triggers reset email
 * send," internal-only (never exposed to webhook consumers, prd-mvp.md's event catalog). Written to
 * the outbox by {@code RequestPasswordResetService} as an audit trail only — unlike {@link
 * AccountRegisteredEvent} (ADR-0007 §1, strictly same-transaction with the account insert, since a
 * consumer-facing webhook may depend on it), this event's own "best-effort" status in the design
 * means it does not need that same atomicity guarantee with the {@code VerificationToken} write.
 */
public record PasswordResetRequestedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static PasswordResetRequestedEvent from(final Account account) {
    return new PasswordResetRequestedEvent(account.id(), account.organizationId(), Instant.now());
  }
}
