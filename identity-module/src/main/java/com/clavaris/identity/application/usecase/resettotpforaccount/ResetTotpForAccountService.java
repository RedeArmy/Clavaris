package com.clavaris.identity.application.usecase.resettotpforaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.application.usecase.suspendaccount.AccountNotFoundException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * ADR-0040. The way back for someone who lost both the app and the recovery codes. It is an
 * operator's decision, audited with the operator's identity, and it also ends any pause on the
 * Account's codes so the person can sign in at once.
 */
public class ResetTotpForAccountService implements ResetTotpForAccountUseCase {

  private final AccountRepository accounts;
  private final TotpCredentialRepository credentials;
  private final TotpRecoveryCodeRepository recoveryCodes;
  private final CodeLockout lockout;
  private final AuditEventRecorder auditEvents;
  private final TransactionOperations transaction;

  public ResetTotpForAccountService(
      final AccountRepository accounts,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final CodeLockout lockout,
      final AuditEventRecorder auditEvents,
      final TransactionOperations transaction) {
    this.accounts = accounts;
    this.credentials = credentials;
    this.recoveryCodes = recoveryCodes;
    this.lockout = lockout;
    this.auditEvents = auditEvents;
    this.transaction = transaction;
  }

  @Override
  public boolean handle(final ResetTotpForAccountCommand command) {
    if (accounts.findOrganizationIdById(command.accountId()).isEmpty()) {
      throw new AccountNotFoundException(command.accountId());
    }
    final Boolean removed =
        transaction.execute(
            status -> {
              final boolean had = credentials.deleteByAccountId(command.accountId());
              recoveryCodes.deleteAll(command.accountId());
              if (had) {
                auditEvents.write(
                    command.actor(),
                    "account.totp_reset",
                    "Account",
                    command.accountId().value().toString(),
                    null);
              }
              return had;
            });
    lockout.onSuccess(CodeLockout.totpCodeKey(command.accountId()));
    return Boolean.TRUE.equals(removed);
  }
}
