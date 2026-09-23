package com.clavaris.identity.application.usecase.updateaccountprofile;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Username;
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
    updatePhoneNumberIfAbsent(account, command);
    assignUsernameIfAbsent(account, command);
    accounts.save(account);

    auditEvents.write(
        command.actor(),
        "account.profile_updated",
        "Account",
        account.id().value().toString(),
        null);
  }

  // Live feature request, 2026-09-22 — user explicitly asked for phone number to follow the exact
  // same "view-only once set, editable only while absent" pattern as username right below, not the
  // originally-implemented "always overwrites" behavior — see UpdateAccountProfileCommand's own
  // Javadoc for the full rationale. No uniqueness check needed here, unlike username: phone number
  // never carried one.
  private void updatePhoneNumberIfAbsent(
      final Account account, final UpdateAccountProfileCommand command) {
    if (command.phoneNumber() != null && account.phoneNumber().isEmpty()) {
      account.updatePhoneNumber(command.phoneNumber());
    }
  }

  // SDE-III review, 2026-09-22 — see UpdateAccountProfileCommand's own Javadoc for why this
  // silently no-ops (never throws Account.assignUsername's own IllegalStateException) once a
  // username is already assigned, same reused registeraccount.UsernameAlreadyRegisteredException
  // (not a parallel type) AdminCreateAccountForOrganizationService's own identical uniqueness
  // check already establishes for the same real conflict.
  private void assignUsernameIfAbsent(
      final Account account, final UpdateAccountProfileCommand command) {
    if (command.username() == null || account.username().isPresent()) {
      return;
    }
    final Username username = new Username(command.username());
    if (accounts.existsByOrganizationIdAndUsername(account.organizationId(), username)) {
      throw new UsernameAlreadyRegisteredException(account.organizationId());
    }
    account.assignUsername(username);
  }
}
