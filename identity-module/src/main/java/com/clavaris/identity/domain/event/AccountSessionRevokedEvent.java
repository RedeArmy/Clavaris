package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * TD-SEC-034 follow-up (SDE-III review, 2026-08-31): {@code account.session_revoked} — written to
 * the transactional outbox (ADR-0007 §1) alongside the audit entry {@code
 * RevokeAccountSessionService} already writes for this same action; same "every real
 * security-relevant account mutation gets one" precedent {@link AccountSuspendedEvent}/{@link
 * AccountReactivatedEvent} already established — this class previously audited a session revocation
 * but never raised an event a future webhook/alerting consumer could react to. {@code sessionId} is
 * the raw, opaque {@code HttpSession} id (BR-DATA-01: not PII) — the one fact a future
 * incident-investigation consumer of this event would actually need to correlate it back to a
 * specific browser/device.
 */
public record AccountSessionRevokedEvent(
    AccountId accountId, OrganizationId organizationId, String sessionId, Instant occurredAt) {

  // "of", matching RefreshTokenReuseDetectedEvent's own static-factory convention — this event,
  // like that one, isn't derived from a fresh Account read; RevokeAccountSessionService already
  // has everything it needs from the command and its own session lookup.
  @SuppressWarnings("PMD.ShortMethodName")
  public static AccountSessionRevokedEvent of(
      final AccountId accountId, final OrganizationId organizationId, final String sessionId) {
    return new AccountSessionRevokedEvent(accountId, organizationId, sessionId, Instant.now());
  }
}
