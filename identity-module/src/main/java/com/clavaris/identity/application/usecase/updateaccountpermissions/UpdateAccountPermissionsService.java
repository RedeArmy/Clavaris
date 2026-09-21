package com.clavaris.identity.application.usecase.updateaccountpermissions;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link UpdateAccountPermissionsUseCase}. */
public class UpdateAccountPermissionsService implements UpdateAccountPermissionsUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;

  public UpdateAccountPermissionsService(
      final AccountRepository accounts, final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final UpdateAccountPermissionsCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    if (command.canDeleteOwnAccount()) {
      account.allowSelfDelete();
    } else {
      account.disallowSelfDelete();
    }
    if (command.bypassesDeviceTrust()) {
      account.enableDeviceTrustBypass();
    } else {
      account.disableDeviceTrustBypass();
    }
    accounts.save(account);

    auditEvents.write(
        command.actor(),
        "account.permissions_updated",
        "Account",
        account.id().value().toString(),
        null);
  }
}
