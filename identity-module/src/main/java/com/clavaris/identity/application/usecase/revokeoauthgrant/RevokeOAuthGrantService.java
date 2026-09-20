package com.clavaris.identity.application.usecase.revokeoauthgrant;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.OAuthGrantsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RevokeOAuthGrantUseCase} — Clerk "OAuth" tab parity (ADR-0026),
 * per-grant revoke. TD-SEC-034: audits every real revocation, same discipline {@code
 * RevokeAccountSessionService} already documents for its own sibling action.
 */
public class RevokeOAuthGrantService implements RevokeOAuthGrantUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RevokeOAuthGrantService.class);

  private final OAuthGrantsRepository grants;
  private final AuditEventRecorder auditEvents;

  public RevokeOAuthGrantService(
      final OAuthGrantsRepository grants, final AuditEventRecorder auditEvents) {
    this.grants = grants;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final RevokeOAuthGrantCommand command) {
    final boolean revoked = grants.revokeById(command.accountId(), command.authorizationId());
    if (!revoked) {
      throw new OAuthGrantNotFoundException(command.authorizationId());
    }
    recordRevocation(command);
  }

  // Same isolation posture TD-SEC-036 documents for RevokeAccountSessionService's own audit
  // write — the real, irreversible deletion above must never be rolled back by an audit failure.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void recordRevocation(final RevokeOAuthGrantCommand command) {
    try {
      auditEvents.write(
          command.actor(),
          "account.oauth_grant_revoked",
          "OAuth2Authorization",
          command.authorizationId(),
          null);
    } catch (final RuntimeException e) {
      LOG.warn("event=oauth_grant_revoked_audit_write_failed", e);
    }
  }
}
