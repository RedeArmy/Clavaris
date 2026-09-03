package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.event.AccountRegisteredEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.service.PasswordPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RegisterAccountUseCase}. {@code @Transactional} is the one place Spring
 * leaks into this class — every type it depends on otherwise (the domain model, the ports) has zero
 * Spring imports.
 */
public class RegisterAccountService implements RegisterAccountUseCase {

  private final AccountRepository accounts;
  private final PasswordHasher hasher;
  private final EventOutboxWriter outbox;

  public RegisterAccountService(
      final AccountRepository accounts,
      final PasswordHasher hasher,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.hasher = hasher;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public AccountId handle(final RegisterAccountCommand command) {
    if (!PasswordPolicy.isSatisfiedBy(command.rawPassword())) {
      throw new WeakPasswordException();
    }

    // Concurrency: two requests can race to register the same email *within the same
    // Organization* between this check and the insert below. This pre-check is a fast-path UX
    // improvement (return a clean 409 instead of surfacing a low-level DB error most of the
    // time), NOT the actual safety mechanism — that's the unique constraint on
    // accounts.(organization_id, email) (data-model.md §3, ADR-0010). The same email in a
    // DIFFERENT Organization is not a conflict at all — command.organizationId() scopes this
    // check by design (BR-ORG-01).
    if (accounts.existsByOrganizationIdAndEmail(command.organizationId(), command.email())) {
      throw new EmailAlreadyRegisteredException(command.organizationId());
    }

    final Account account = Account.register(command.organizationId(), command.email());
    account.attachPasswordCredential(hasher.hash(command.rawPassword())); // BR-ID-01

    try {
      accounts.save(account);
    } catch (DataIntegrityViolationException raceLost) {
      // The pre-check above lost the race — another request committed first. Translate the
      // low-level DB exception into the same domain exception the pre-check would have thrown, so
      // the caller (the web adapter) never needs to know a race occurred — but keep raceLost as
      // the cause, not discard it, so a production investigation still has the real JDBC-level
      // detail available.
      throw new EmailAlreadyRegisteredException(command.organizationId(), raceLost);
    }

    // ADR-0007 §1: outbox row written in the SAME transaction as the account insert —
    // @Transactional above covers both, so a crash between the two is impossible; either both
    // commit or neither does.
    outbox.write(
        "account.created",
        account.id(),
        account.organizationId(),
        AccountRegisteredEvent.from(account));

    return account.id();
  }
}
