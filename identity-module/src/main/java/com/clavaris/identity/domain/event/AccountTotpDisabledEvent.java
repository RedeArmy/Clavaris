package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.totp_disabled} (TD-FUT-068): the End-User removed the authenticator app from their
 * own Account, after proving it with a code. A consuming application that mirrors an Account's
 * sign-in methods learns of it here instead of polling. Carries no secret, no code and no recovery
 * code: only who and when.
 */
public record AccountTotpDisabledEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static AccountTotpDisabledEvent of(
      final AccountId accountId, final OrganizationId organizationId) {
    return new AccountTotpDisabledEvent(accountId, organizationId, Instant.now());
  }
}
