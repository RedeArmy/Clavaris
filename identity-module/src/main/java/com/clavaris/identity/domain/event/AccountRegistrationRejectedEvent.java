package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * TD-FUT-019: {@code account.registration_rejected} — written by {@code
 * RejectAccountRegistrationService} in the same transaction as the {@code AccountStatus.REJECTED}
 * transition. {@code reason} mirrors {@code Account#registrationRejectionReason()} — may be {@code
 * null}, same "no reason given" caveat that field's own Javadoc documents.
 */
public record AccountRegistrationRejectedEvent(
    AccountId accountId,
    OrganizationId organizationId,
    String email,
    String reason,
    Instant occurredAt) {

  public static AccountRegistrationRejectedEvent from(final Account account) {
    return new AccountRegistrationRejectedEvent(
        account.id(),
        account.organizationId(),
        account.email().value(),
        account.registrationRejectionReason().orElse(null),
        Instant.now());
  }
}
