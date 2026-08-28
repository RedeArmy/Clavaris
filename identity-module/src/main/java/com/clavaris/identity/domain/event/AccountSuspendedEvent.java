package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.suspended} — written to the transactional outbox (ADR-0007 §1) in the same
 * transaction as the state transition itself. Unlike {@link AccountDeletedEvent}, the {@code
 * Account} row still exists after this event, so there is no "last point this field is available"
 * justification for carrying anything beyond ids — no email (BR-DATA-01), same discipline every
 * other non-terminal event in this codebase follows.
 */
public record AccountSuspendedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static AccountSuspendedEvent from(final Account account) {
    return new AccountSuspendedEvent(account.id(), account.organizationId(), Instant.now());
  }
}
