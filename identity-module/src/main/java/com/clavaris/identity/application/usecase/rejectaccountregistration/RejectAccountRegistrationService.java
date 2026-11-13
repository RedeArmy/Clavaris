package com.clavaris.identity.application.usecase.rejectaccountregistration;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.AccountRegistrationRejectedEvent;
import com.clavaris.identity.domain.model.Account;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RejectAccountRegistrationUseCase}. Same "never had a live session/token
 * to revoke" rationale as {@code ApproveAccountRegistrationService}'s own Javadoc.
 */
public class RejectAccountRegistrationService implements RejectAccountRegistrationUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;
  private final Clock clock;

  public RejectAccountRegistrationService(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final Clock clock) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void handle(final RejectAccountRegistrationCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.rejectRegistration(command.reason(), clock.instant());
    accounts.save(account);

    auditEvents.write(
        command.actor(),
        "account.registration_rejected",
        "Account",
        account.id().value().toString(),
        command.reason());

    outbox.write(
        "account.registration_rejected",
        account.id(),
        account.organizationId(),
        AccountRegistrationRejectedEvent.from(account));
  }
}
