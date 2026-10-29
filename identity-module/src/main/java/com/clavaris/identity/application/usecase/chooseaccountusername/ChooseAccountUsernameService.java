package com.clavaris.identity.application.usecase.chooseaccountusername;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.updateaccountprofile.AccountNotFoundException;
import com.clavaris.identity.domain.event.AccountProfileUpdatedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Username;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ChooseAccountUsernameUseCase}. Only ever fills an absent username (the
 * same "set once, from here on view-only" posture {@code UpdateAccountProfileService} applies), so
 * a repeated or replayed submission can never overwrite one already chosen. A dedicated use case
 * rather than {@code UpdateAccountProfileCommand}: that one also rewrites the first and last name,
 * which here would have to be passed back unchanged to avoid blanking what the provider gave.
 *
 * <p>Uniqueness is {@code (organization_id, username)}: the same name may exist in another
 * Organization. The pre-check gives a clean error; the unique index remains the backstop for two
 * people choosing the same name at once, reported the same way.
 */
public class ChooseAccountUsernameService implements ChooseAccountUsernameUseCase {

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public ChooseAccountUsernameService(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final ChooseAccountUsernameCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));
    if (account.username().isPresent()) {
      return;
    }
    final Username username = new Username(command.rawUsername());
    if (accounts.existsByOrganizationIdAndUsername(account.organizationId(), username)) {
      throw new UsernameAlreadyRegisteredException(account.organizationId());
    }
    account.assignUsername(username);
    try {
      accounts.save(account);
    } catch (final DataIntegrityViolationException raced) {
      throw new UsernameAlreadyRegisteredException(account.organizationId(), raced);
    }

    auditEvents.write(
        command.actor(),
        "account.username_chosen",
        "Account",
        account.id().value().toString(),
        null);
    outbox.write(
        "account.profile_updated",
        account.id(),
        account.organizationId(),
        AccountProfileUpdatedEvent.from(account));
  }
}
