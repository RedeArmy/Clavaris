package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * domain-model.md §6: {@code account.email_verified} — exposed to future webhook consumers
 * (prd-mvp.md's event catalog), unlike {@code refresh_token.reuse_detected}/{@code
 * PasswordResetRequestedEvent}, which stay internal-only. Written to the transactional outbox by
 * {@code ConfirmEmailVerificationService} only after {@link Account#verifyEmail()} has already
 * succeeded (ADR-0007 §1) — this record is only the payload shape, not the delivery mechanism.
 */
public record AccountEmailVerifiedEvent(
    AccountId accountId, OrganizationId organizationId, String email, Instant occurredAt) {

  public static AccountEmailVerifiedEvent from(final Account account) {
    return new AccountEmailVerifiedEvent(
        account.id(), account.organizationId(), account.email().value(), Instant.now());
  }
}
