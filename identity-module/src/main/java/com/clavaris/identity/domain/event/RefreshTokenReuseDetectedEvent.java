package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * domain-model.md §6/§7, BR-ID-03: {@code refresh_token.reuse_detected} — unlike {@link
 * AccountRegisteredEvent}, this fires only AFTER the invariant it describes has already been
 * enforced synchronously (every active token for the account revoked, {@code
 * RotateRefreshTokenService}'s own transaction) — the security alert this becomes (NFR
 * `nfr-quality-attributes.md` §5: "a reuse detection firing is a security signal worth alerting on,
 * not just logging") is a best-effort side effect of the revocation, never a gate on it.
 */
public record RefreshTokenReuseDetectedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  // "of", matching AccountRegisteredEvent's own "from" static-factory convention family — a short,
  // conventional factory name, not an accidental abbreviation.
  @SuppressWarnings("PMD.ShortMethodName")
  public static RefreshTokenReuseDetectedEvent of(
      final AccountId accountId, final OrganizationId organizationId) {
    return new RefreshTokenReuseDetectedEvent(accountId, organizationId, Instant.now());
  }
}
