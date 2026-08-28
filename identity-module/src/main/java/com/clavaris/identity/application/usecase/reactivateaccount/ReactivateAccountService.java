package com.clavaris.identity.application.usecase.reactivateaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.AccountReactivatedEvent;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link ReactivateAccountUseCase}. */
public class ReactivateAccountService implements ReactivateAccountUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public ReactivateAccountService(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final ReactivateAccountCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.reactivate();
    accounts.save(account);

    auditEvents.write(
        command.actor(), "account.reactivated", "Account", account.id().value().toString(), null);

    outbox.write("account.reactivated", account.id(), AccountReactivatedEvent.from(account));
  }
}
