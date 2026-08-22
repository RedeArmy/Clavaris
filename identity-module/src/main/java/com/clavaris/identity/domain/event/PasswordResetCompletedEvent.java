package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * domain-model.md §6: {@code PasswordResetCompletedEvent} — internal-only (never exposed to webhook
 * consumers). "Invariant cascade, already completed before this event is raised": by the time this
 * is written, {@code ConfirmPasswordResetService} has already revoked every active session/refresh
 * token/authorization-service token for the account (BR-ID-04) — this record is a fact about
 * something that already happened, not a trigger for the cascade itself.
 */
public record PasswordResetCompletedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static PasswordResetCompletedEvent from(final Account account) {
    return new PasswordResetCompletedEvent(account.id(), account.organizationId(), Instant.now());
  }
}
