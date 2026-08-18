package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * domain-model.md §6: {@code account.created} — best-effort, triggers the email verification send
 * (async, retryable; a delayed verification email is not a correctness issue). Written to the
 * transactional outbox in the same database transaction as the {@code Account} insert (ADR-0007 §1)
 * by {@code EventOutboxWriter}, not published directly — this record is only the payload shape, not
 * the delivery mechanism.
 */
public record AccountRegisteredEvent(
    AccountId accountId, OrganizationId organizationId, String email, Instant occurredAt) {

  public static AccountRegisteredEvent from(final Account account) {
    return new AccountRegisteredEvent(
        account.id(), account.organizationId(), account.email().value(), Instant.now());
  }
}
