package com.clavaris.identity.application.usecase.unbanaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.AccountUnbannedEvent;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link UnbanAccountUseCase}. */
public class UnbanAccountService implements UnbanAccountUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public UnbanAccountService(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final UnbanAccountCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.unban();
    accounts.save(account);

    auditEvents.write(
        command.actor(), "account.unbanned", "Account", account.id().value().toString(), null);

    outbox.write(
        "account.unbanned",
        account.id(),
        account.organizationId(),
        AccountUnbannedEvent.from(account));
  }
}
