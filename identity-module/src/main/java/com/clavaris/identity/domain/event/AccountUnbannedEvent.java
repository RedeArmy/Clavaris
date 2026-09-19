package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.unbanned} — SDE-III review, 2026-09-19, Clerk dashboard "Users" parity. See {@link
 * AccountSuspendedEvent}'s own Javadoc for the shape rationale.
 */
public record AccountUnbannedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static AccountUnbannedEvent from(final Account account) {
    return new AccountUnbannedEvent(account.id(), account.organizationId(), Instant.now());
  }
}
