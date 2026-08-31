package com.clavaris.identity.application.usecase.revokeaccountsession;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;

/**
 * Orchestration for {@link RevokeAccountSessionUseCase}. Reuses {@code
 * listactivesessionsforaccount}'s own {@link AccountActiveSessionsRepository} rather than a
 * dedicated port — same "one port, several use cases" precedent as {@code AccountRepository}.
 *
 * <p>TD-SEC-034 (SDE-III review, 2026-08-31): audits every real revocation — {@code
 * SuspendAccountService}/{@code ReactivateAccountService}/{@code DeleteAccountService} all leave an
 * audit trail for the security-relevant account mutation they perform; an Account ending its own
 * live session is exactly the class of event an incident investigation would want reconstructed
 * later ("when was this session actually killed, from where"), and this class previously left none.
 * Always {@link AuditActor#account} — {@code command.accountId()} is both the actor and the target
 * here, genuine self-service, never an operator acting on someone else's behalf.
 */
public class RevokeAccountSessionService implements RevokeAccountSessionUseCase {

  private final AccountActiveSessionsRepository activeSessions;
  private final AuditEventRecorder auditEvents;

  public RevokeAccountSessionService(
      final AccountActiveSessionsRepository activeSessions, final AuditEventRecorder auditEvents) {
    this.activeSessions = activeSessions;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final RevokeAccountSessionCommand command) {
    // The ownership check itself: command.accountId() is always the caller's own session
    // principal (never client input), so this lookup can only ever resolve a session if it both
    // exists and belongs to the caller — same outcome (SessionNotFoundException) either way a
    // mismatched sessionId fails, by design (see that exception's own Javadoc).
    final ActiveAccountSession session =
        activeSessions
            .findByAccountIdAndSessionId(command.accountId(), command.sessionId())
            .orElseThrow(() -> new SessionNotFoundException(command.sessionId()));

    activeSessions.revoke(session.sessionId());

    auditEvents.write(
        AuditActor.account(command.accountId().value()),
        "account.session_revoked",
        "Session",
        session.sessionId(),
        null);
  }
}
