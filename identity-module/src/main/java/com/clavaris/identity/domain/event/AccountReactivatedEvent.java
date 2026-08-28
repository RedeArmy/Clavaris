package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.reactivated} — see {@link AccountSuspendedEvent}'s own Javadoc for the shape
 * rationale.
 */
public record AccountReactivatedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static AccountReactivatedEvent from(final Account account) {
    return new AccountReactivatedEvent(account.id(), account.organizationId(), Instant.now());
  }
}
