package com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RevokeAllOAuthGrantsForAccountUseCase} — Clerk "OAuth" tab parity
 * (ADR-0026), the "Revoke all" button. Reuses {@link AccountTokenRevoker#revokeAllTokensFor}
 * (BR-ID-03's own reuse-detection cascade already calls this port) rather than a second delete
 * implementation — this service's only added job is making the action independently auditable when
 * an operator triggers it directly, distinct from an automatic security cascade.
 */
public class RevokeAllOAuthGrantsForAccountService
    implements RevokeAllOAuthGrantsForAccountUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RevokeAllOAuthGrantsForAccountService.class);

  private final AccountTokenRevoker tokenRevoker;
  private final AuditEventRecorder auditEvents;

  public RevokeAllOAuthGrantsForAccountService(
      final AccountTokenRevoker tokenRevoker, final AuditEventRecorder auditEvents) {
    this.tokenRevoker = tokenRevoker;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final RevokeAllOAuthGrantsForAccountCommand command) {
    tokenRevoker.revokeAllTokensFor(command.accountId());
    recordRevocation(command);
  }

  // Same isolation posture TD-SEC-036 documents for RevokeAccountSessionService's own audit
  // write — the real, irreversible deletion above must never be rolled back by an audit failure.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void recordRevocation(final RevokeAllOAuthGrantsForAccountCommand command) {
    try {
      auditEvents.write(
          command.actor(),
          "account.oauth_grants_revoked_all",
          "Account",
          command.accountId().value().toString(),
          null);
    } catch (final RuntimeException e) {
      LOG.warn("event=oauth_grants_revoked_all_audit_write_failed", e);
    }
  }
}
