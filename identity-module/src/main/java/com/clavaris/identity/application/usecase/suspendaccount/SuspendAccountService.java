package com.clavaris.identity.application.usecase.suspendaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
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
 */
// Literals: the repeated string is "PMD.LongVariable" itself, used on the constructor's port
// parameters — same rationale as identity-module's own IdentityUseCaseConfig/DeleteAccountService
// class-level suppression for this exact PMD-annotation-string-as-literal false positive.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class SuspendAccountService implements SuspendAccountUseCase {

  private final AccountRepository accounts;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as every
  // other caller of this port (DeleteAccountService, RotateRefreshTokenService).
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as
  // accountTokenRevoker above.
  private final AccountSessionRevoker accountSessionRevoker;

  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // DeleteAccountService's own identical suppression.
  public SuspendAccountService(
      final AccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
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
