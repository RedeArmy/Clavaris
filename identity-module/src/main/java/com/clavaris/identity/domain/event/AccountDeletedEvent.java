package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * BR-DATA-02/03: {@code account.deleted} — written to the transactional outbox (ADR-0007 §1) in the
 * same database transaction as the hard-delete itself, same "payload shape, not the delivery
 * mechanism" convention as {@link AccountRegisteredEvent}. {@code email} is captured here, not
 * re-read after the fact — by the time this event is constructed the {@code Account} row it
 * describes is already gone, so this is the last point in the whole operation where that field is
 * still available at all.
 */
public record AccountDeletedEvent(
    AccountId accountId, OrganizationId organizationId, String email, Instant occurredAt) {

  public static AccountDeletedEvent from(final Account account) {
    return new AccountDeletedEvent(
        account.id(), account.organizationId(), account.email().value(), Instant.now());
  }
}
