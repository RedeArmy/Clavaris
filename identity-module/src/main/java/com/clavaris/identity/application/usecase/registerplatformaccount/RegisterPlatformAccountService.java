package com.clavaris.identity.application.usecase.registerplatformaccount;

import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.service.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RegisterPlatformAccountUseCase}. Mirrors {@code
 * registeraccount.RegisterAccountService} exactly (same concurrency race/pre-check shape), minus
 * the outbox write — a {@code PlatformAccount} belongs to no {@code Organization}, so there is no
 * webhook consumer this event could ever be scoped to; a structured {@code event=} log line
 * (TD-SEC-014's own convention) is the audit trail instead.
 */
public class RegisterPlatformAccountService implements RegisterPlatformAccountUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RegisterPlatformAccountService.class);

  private final PlatformAccountRepository accounts;
  private final PasswordHasher hasher;

  public RegisterPlatformAccountService(
      final PlatformAccountRepository accounts, final PasswordHasher hasher) {
    this.accounts = accounts;
    this.hasher = hasher;
  }

  @SuppressWarnings("PMD.GuardLogStatement") // same false-positive rationale as
  // AuthenticateWithPasswordService's own identical suppression.
  @Override
  @Transactional
  public PlatformAccountId handle(final RegisterPlatformAccountCommand command) {
    if (!PasswordPolicy.isSatisfiedBy(command.rawPassword())) {
      throw new WeakPasswordException();
    }

    if (accounts.existsByEmail(command.email())) {
      throw new PlatformAccountEmailAlreadyRegisteredException(command.email());
    }

    final PlatformAccount account = PlatformAccount.register(command.email());
    account.attachPasswordCredential(hasher.hash(command.rawPassword()));

    try {
      accounts.save(account);
    } catch (final DataIntegrityViolationException raceLost) {
      throw new PlatformAccountEmailAlreadyRegisteredException(command.email(), raceLost);
    }

    LOG.info("event=platform_account_registered platformAccountId={}", account.id());
    return account.id();
  }
}
