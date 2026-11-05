package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.totp_reset} (TD-FUT-068): an operator removed the authenticator app because the
 * person lost the device. A consuming application that mirrors an Account's sign-in methods learns
 * of it here instead of polling. Carries no secret, no code and no recovery code: only who and
 * when.
 */
public record AccountTotpResetEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static AccountTotpResetEvent of(
      final AccountId accountId, final OrganizationId organizationId) {
    return new AccountTotpResetEvent(accountId, organizationId, Instant.now());
  }
}
