package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.email_changed} (TD-FUT-043): the End-User moved their Account to a new, verified
 * address. A consuming application that keeps its own copy of the email has no other way to learn
 * it changed, so this is exposed to webhook consumers like {@link AccountEmailVerifiedEvent}. It
 * carries both addresses so a consumer can find its record by the old one. Written to the outbox in
 * the same transaction as the change (ADR-0007 §1).
 */
public record AccountEmailChangedEvent(
    AccountId accountId,
    OrganizationId organizationId,
    String previousEmail,
    String email,
    Instant occurredAt) {

  public static AccountEmailChangedEvent from(final Account account, final String previousEmail) {
    return new AccountEmailChangedEvent(
        account.id(),
        account.organizationId(),
        previousEmail,
        account.email().value(),
        Instant.now());
  }
}
