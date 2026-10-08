package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.time.Instant;

/**
 * TD-FUT-044 (Clerk {@code user.updated} parity, found during a 2026-10-07 functional-testing
 * session): {@code account.profile_updated} — the "both directions" half of self-service/
 * Backend-API profile sync (TD-FUT-040/041) that was missing. Written to the transactional outbox
 * in the same database transaction as the {@code Account} update (ADR-0007 §1) by {@link
 * com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter}, same convention
 * {@link AccountRegisteredEvent} already establishes — this record is only the payload shape, not
 * the delivery mechanism. A full post-update snapshot of the fields {@code
 * UpdateAccountProfileUseCase} can touch, not a diff — same "snapshot the Account's own current
 * state" posture {@link AccountRegisteredEvent#from} already takes, so a consumer that missed an
 * intermediate delivery (retry exhaustion, a brand-new subscription) still ends up correct on its
 * next one, not merely informed that *something* changed.
 */
public record AccountProfileUpdatedEvent(
    AccountId accountId,
    OrganizationId organizationId,
    String firstName,
    String lastName,
    String username,
    String phoneNumber,
    Instant occurredAt) {

  public static AccountProfileUpdatedEvent from(final Account account) {
    return new AccountProfileUpdatedEvent(
        account.id(),
        account.organizationId(),
        account.firstName().orElse(null),
        account.lastName().orElse(null),
        account.username().map(Username::value).orElse(null),
        account.phoneNumber().orElse(null),
        Instant.now());
  }
}
