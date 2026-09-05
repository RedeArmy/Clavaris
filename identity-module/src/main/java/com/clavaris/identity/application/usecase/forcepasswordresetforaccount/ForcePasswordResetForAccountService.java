package com.clavaris.identity.application.usecase.forcepasswordresetforaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ForcePasswordResetForAccountUseCase}. Deliberately does not revoke
 * existing sessions/tokens the way {@code SuspendAccountService}/{@code
 * ConfirmPasswordResetService} do — forcing a future reset is not itself an emergency lockout (an
 * operator who also wants that calls {@code SuspendAccountController} separately); this only ever
 * changes what the account's *next* fresh login must complete, via {@code SessionTaskGate}.
 */
public class ForcePasswordResetForAccountService implements ForcePasswordResetForAccountUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;

  public ForcePasswordResetForAccountService(
      final AccountRepository accounts, final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final ForcePasswordResetForAccountCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.requirePasswordReset();
    accounts.save(account);

    auditEvents.write(
        command.actor(),
        "account.password_reset_required",
        "Account",
        account.id().value().toString(),
        null);
  }
}
