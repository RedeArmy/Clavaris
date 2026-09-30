package com.clavaris.identity.application.usecase.approveaccountregistration;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.AccountRegistrationApprovedEvent;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ApproveAccountRegistrationUseCase}. Deliberately does not touch
 * sessions/tokens — a {@code PENDING_APPROVAL} account has never had a live session or refresh
 * token in the first place (both {@code RegisterAccountController} and {@code
 * AuthenticateWithSocialProviderService} defer {@code establish*} entirely while gated, see their
 * own TD-FUT-019 Javadoc), unlike {@code SuspendAccountService}'s revocation cascade, which exists
 * specifically to kill an already-live session.
 */
public class ApproveAccountRegistrationService implements ApproveAccountRegistrationUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public ApproveAccountRegistrationService(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final ApproveAccountRegistrationCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.approveRegistration();
    accounts.save(account);

    auditEvents.write(
        command.actor(),
        "account.registration_approved",
        "Account",
        account.id().value().toString(),
        null);

    outbox.write(
        "account.registration_approved",
        account.id(),
        account.organizationId(),
        AccountRegistrationApprovedEvent.from(account));
  }
}
