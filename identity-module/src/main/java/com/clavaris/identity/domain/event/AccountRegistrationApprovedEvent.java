package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * TD-FUT-019: {@code account.registration_approved} — written by {@code
 * ApproveAccountRegistrationService} in the same transaction as the {@code AccountStatus.ACTIVE}
 * transition, so a consuming application's own backend (or the Clavaris admin dashboard) learns a
 * previously {@code PENDING_APPROVAL} account can now sign in.
 */
public record AccountRegistrationApprovedEvent(
    AccountId accountId, OrganizationId organizationId, String email, Instant occurredAt) {

  public static AccountRegistrationApprovedEvent from(final Account account) {
    return new AccountRegistrationApprovedEvent(
        account.id(), account.organizationId(), account.email().value(), Instant.now());
  }
}
