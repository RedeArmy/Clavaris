package com.clavaris.identity.application.usecase.registerplatformaccount;

import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.service.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

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

  private final BreachedPasswordChecker breachedPasswordChecker;
  private final TransactionOperations transaction;

  public RegisterPlatformAccountService(
      final PlatformAccountRepository accounts,
      final PasswordHasher hasher,
      final BreachedPasswordChecker breachedPasswordChecker,
      final TransactionOperations transaction) {
    this.accounts = accounts;
    this.hasher = hasher;
    this.breachedPasswordChecker = breachedPasswordChecker;
    this.transaction = transaction;
  }

  @Override
  public PlatformAccountId handle(final RegisterPlatformAccountCommand command) {
    if (!PasswordPolicy.isSatisfiedBy(command.rawPassword())) {
      throw new WeakPasswordException();
    }
    // BR-ID-07, CLAUDE.md §5: the platform tier is the single highest-value target in the whole
    // system — this check applies here unconditionally, same as every other tier.
    if (breachedPasswordChecker.isBreached(command.rawPassword())) {
      throw new BreachedPasswordException();
    }

    if (accounts.existsByEmail(command.email())) {
      throw new PlatformAccountEmailAlreadyRegisteredException();
    }

    // ARGON2 OUTSIDE ANY TRANSACTION (TD-PERF-053, as in RegisterAccountService, TD-PERF-031): the
    // hash waits on the Argon2 bulkhead and costs tens of milliseconds, and the pool is ten
    // connections. Only the insert needs a transaction.
    final PlatformAccount account = PlatformAccount.register(command.email());
    account.attachPasswordCredential(hasher.hash(command.rawPassword()));

    try {
      // TD-PERF-019: insert, not save — the pre-check above already confirmed no row exists, and
      // this is the one place that ever constructs a PlatformAccount fresh. See
      // PlatformAccountRepository#insert's own Javadoc.
      transaction.executeWithoutResult(status -> accounts.insert(account));
    } catch (final DataIntegrityViolationException raceLost) {
      throw new PlatformAccountEmailAlreadyRegisteredException(raceLost);
    }

    LOG.info("event=platform_account_registered platformAccountId={}", account.id());
    return account.id();
  }
}
