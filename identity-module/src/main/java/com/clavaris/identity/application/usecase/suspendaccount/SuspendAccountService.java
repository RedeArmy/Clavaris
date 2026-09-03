package com.clavaris.identity.application.usecase.suspendaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.event.AccountSuspendedEvent;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link SuspendAccountUseCase}. Reuses the exact same revocation ports {@code
 * DeleteAccountService} already calls ({@link AccountTokenRevoker}/{@link AccountSessionRevoker}) —
 * a ban must kill an already-live session/token immediately, not merely block future logins; {@code
 * AuthenticateWithPasswordService} already covers the "future logins" half on its own, since it
 * rejects any non-{@code ACTIVE} account before touching the password hash.
 *
 * <p><b>SDE-III review, 2026-09-03 — real gap found and closed:</b> the two revoker ports above
 * only ever reach the SAS-managed access/ID token and the hosted-login {@code HttpSession}; this
 * module's own {@link com.clavaris.identity.domain.model.Session}/{@link
 * com.clavaris.identity.domain.model.RefreshToken} rows were never touched, so a client already
 * holding a refresh token kept minting fresh access/refresh pairs via {@code
 * RotateRefreshTokenService} indefinitely — suspension didn't actually suspend anything for a
 * refresh-token-holding client. Now runs the exact same {@code sessions}/{@code refreshTokens}
 * cascade {@code ConfirmPasswordResetService}'s own BR-ID-04 response already uses (same two ports,
 * same call order), and {@code RotateRefreshTokenService} independently re-checks {@code
 * AccountStatus} on every rotation as a backstop — belt and suspenders, not either/or, since a
 * revocation cascade racing a concurrent rotation is exactly the class of gap a status check alone
 * can't close and vice versa.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, used on the constructor's port
// parameters — same rationale as identity-module's own IdentityUseCaseConfig/DeleteAccountService
// class-level suppression for this exact PMD-annotation-string-as-literal false positive.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class SuspendAccountService implements SuspendAccountUseCase {

  private final AccountRepository accounts;
  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as every
  // other caller of this port (DeleteAccountService, RotateRefreshTokenService).
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as
  // accountTokenRevoker above.
  private final AccountSessionRevoker accountSessionRevoker;

  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // ConfirmPasswordResetService's own identical suppression for the same BR-ID-04-shaped cascade.
  public SuspendAccountService(
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.accountTokenRevoker = accountTokenRevoker;
    this.accountSessionRevoker = accountSessionRevoker;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final SuspendAccountCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.suspend();
    accounts.save(account);

    // BR-ID-04-shaped cascade (see this class's own Javadoc, SDE-III review 2026-09-03) — identical
    // call order to ConfirmPasswordResetService's own reset-response cascade.
    sessions.revokeAllActiveForAccount(account.id());
    refreshTokens.revokeAllActiveForAccount(account.id());
    accountTokenRevoker.revokeAllTokensFor(account.id());
    accountSessionRevoker.revokeAllSessionsFor(account.id());

    auditEvents.write(
        command.actor(), "account.suspended", "Account", account.id().value().toString(), null);

    outbox.write(
        "account.suspended",
        account.id(),
        account.organizationId(),
        AccountSuspendedEvent.from(account));
  }
}
