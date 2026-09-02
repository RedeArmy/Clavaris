package com.clavaris.identity.application.usecase.revokeaccountsession;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BestEffortEventPublisher;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.AccountSessionRevokedEvent;
import com.clavaris.identity.domain.model.AccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RevokeAccountSessionUseCase}. Reuses {@code
 * listactivesessionsforaccount}'s own {@link AccountActiveSessionsRepository} rather than a
 * dedicated port — same "one port, several use cases" precedent as {@code AccountRepository}.
 *
 * <p>TD-SEC-034: audits every real revocation, same as {@code SuspendAccountService}/{@code
 * ReactivateAccountService}/{@code DeleteAccountService} do for their own security-relevant
 * mutations. Always {@link AuditActor#account} — genuine self-service, never an operator acting on
 * someone else's behalf.
 *
 * <p><b>TD-SEC-036: deliberately NOT {@code @Transactional}.</b> {@link
 * AccountActiveSessionsRepository#revoke} is a Redis call, already irreversible before any Postgres
 * write below it runs — wrapping a transaction around the writes that follow would only risk
 * rolling back an already-completed revoke's own audit trail if one of them failed. {@link
 * #recordRevocation} isolates each write independently via {@link BestEffortEventPublisher} / its
 * own try/catch — see technical-debt-register.md TD-SEC-036 for the full incident history.
 */
public class RevokeAccountSessionService implements RevokeAccountSessionUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RevokeAccountSessionService.class);

  private final AccountActiveSessionsRepository activeSessions;
  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public RevokeAccountSessionService(
      final AccountActiveSessionsRepository activeSessions,
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.activeSessions = activeSessions;
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
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

    // The real, irreversible action, first and unconditionally — never gated on Postgres health.
    activeSessions.revoke(session.sessionId());

    recordRevocation(command.accountId(), session.sessionId());
  }

  // Audit and outbox are isolated independently (TD-SEC-036) — neither may propagate, and a
  // failure in one must never suppress the other's own attempt.
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // AuditEventRecorder/AccountRepository
  // can each throw a Spring DataAccessException like any other DB call.
  private void recordRevocation(final AccountId accountId, final String sessionId) {
    try {
      auditEvents.write(
          AuditActor.account(accountId.value()),
          "account.session_revoked",
          "Session",
          sessionId,
          null);
    } catch (final RuntimeException e) {
      LOG.warn("event=account_session_revoked_audit_write_failed", e);
    }

    // findOrganizationIdById, not findById: the outbox event only needs this one field, not the
    // full Account + its own separate PasswordCredential lookup.
    try {
      accounts
          .findOrganizationIdById(accountId)
          .ifPresentOrElse(
              organizationId ->
                  BestEffortEventPublisher.publish(
                      LOG,
                      outbox,
                      "account.session_revoked",
                      accountId,
                      organizationId,
                      AccountSessionRevokedEvent.of(accountId, organizationId, sessionId),
                      "event=account_session_revoked_outbox_write_failed"),
              () -> LOG.warn("event=account_session_revoked_outbox_skipped_account_not_found"));
    } catch (final RuntimeException e) {
      LOG.warn("event=account_session_revoked_account_lookup_failed", e);
    }
  }
}
