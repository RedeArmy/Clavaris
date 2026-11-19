package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * TD-SEC-103 / BR-ID-03: a refresh token that had already been rotated away was presented again
 * inside its Organization's reuse window and, because its successor had not been used, was answered
 * with a new token instead of ending the Session. Deliberately its own type, apart from {@link
 * RefreshTokenReuseDetectedEvent}: a flood of these is a client losing responses, or a thief racing
 * the real client, and that must stay distinguishable from a confirmed theft.
 */
public record RefreshTokenReuseGracedEvent(
    AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  // The instant comes from the use case's clock, not from here: the domain does not read the wall
  // clock (TD-ARCH-052, DomainClockUsageTest).
  public static RefreshTokenReuseGracedEvent of(
      final AccountId accountId, final OrganizationId organizationId, final Instant occurredAt) {
    return new RefreshTokenReuseGracedEvent(accountId, organizationId, occurredAt);
  }
}
