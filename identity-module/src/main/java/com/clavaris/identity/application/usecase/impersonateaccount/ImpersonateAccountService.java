package com.clavaris.identity.application.usecase.impersonateaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ImpersonateAccountUseCase}. Deliberately does not mint anything itself —
 * see {@link ImpersonateAccountCommand}'s own Javadoc for why token issuance lives in the {@code
 * app} module instead. {@code @Transactional} for the same reason every other audited mutation in
 * this module is: the audit row must land whether or not anything downstream in the {@code app}
 * module's own token-minting step later fails, since "an operator looked up and was authorized to
 * impersonate this Account" is itself the fact worth recording, independent of whether a token was
 * ultimately handed back.
 */
public class ImpersonateAccountService implements ImpersonateAccountUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;

  public ImpersonateAccountService(
      final AccountRepository accounts, final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public ImpersonateAccountResult handle(final ImpersonateAccountCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    if (account.status() != AccountStatus.ACTIVE) {
      throw new AccountNotActiveException(command.accountId());
    }

    // High-sensitivity action (§6 non-negotiable posture, threat-model-stride.md) — always audited,
    // never best-effort, same discipline as every other admin-API mutation in this module.
    auditEvents.write(
        command.actor(),
        "account.impersonation_started",
        "Account",
        account.id().value().toString(),
        null);

    return new ImpersonateAccountResult(account.id(), account.organizationId());
  }
}
