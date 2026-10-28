package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.password_changed} (TD-FUT-043): the End-User changed their own password while
 * signed in. A consuming application that keeps its own sessions needs to know the credential
 * changed so it can end them, exactly as Clavaris ends its own (BR-ID-04). Deliberately carries no
 * secret and no hash: only who and when. The forgot-password path has its own event, {@code
 * password_reset.completed}.
 */
public record AccountPasswordChangedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static AccountPasswordChangedEvent from(final Account account) {
    return new AccountPasswordChangedEvent(account.id(), account.organizationId(), Instant.now());
  }
}
