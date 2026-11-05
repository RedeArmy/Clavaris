package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * {@code account.totp_enrolled} (TD-FUT-068): an authenticator app was confirmed on the Account, so
 * sign-in now asks for its code. A consuming application that mirrors an Account's sign-in methods
 * learns of it here instead of polling. Carries no secret, no code and no recovery code: only who
 * and when.
 */
public record AccountTotpEnrolledEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public static AccountTotpEnrolledEvent of(
      final AccountId accountId, final OrganizationId organizationId) {
    return new AccountTotpEnrolledEvent(accountId, organizationId, Instant.now());
  }
}
