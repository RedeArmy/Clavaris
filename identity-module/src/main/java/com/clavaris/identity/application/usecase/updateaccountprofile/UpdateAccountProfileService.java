package com.clavaris.identity.application.usecase.updateaccountprofile;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link UpdateAccountProfileUseCase}. */
public class UpdateAccountProfileService implements UpdateAccountProfileUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;

  public UpdateAccountProfileService(
      final AccountRepository accounts, final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final UpdateAccountProfileCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.updateProfile(command.firstName(), command.lastName());
    accounts.save(account);

    auditEvents.write(
        command.actor(),
        "account.profile_updated",
        "Account",
        account.id().value().toString(),
        null);
  }
}
