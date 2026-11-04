package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.PasswordPolicy;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Orchestration for {@link ConfirmPlatformAccountPasswordResetUseCase}. ADR-0012's equivalent of
 * BR-ID-04: a successful reset revokes every {@code HttpSession} Spring Security's {@code
 * SessionRegistry} knows about for this account (via {@link PlatformAccountSessionRevoker}), not a
 * refresh-token cascade — see that port's own Javadoc.
 *
 * <p>SDE-III review, 2026-09-15: token consumption goes through {@link
 * PlatformVerificationTokenRepository#consumeIfActive}, closing the same TOCTOU {@code
 * confirmpasswordreset.ConfirmPasswordResetService}'s own Javadoc documents for the tenant tier —
 * same race, this tier's own reset flow.
 */
public class ConfirmPlatformAccountPasswordResetService
    implements ConfirmPlatformAccountPasswordResetUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConfirmPlatformAccountPasswordResetService.class);

  private final PlatformVerificationTokenRepository tokens;
  private final PlatformAccountRepository accounts;
  private final PlatformAccountSessionRevoker sessionRevoker;
  private final PasswordHasher hasher;

  private final BreachedPasswordChecker breachedPasswordChecker;
  private final TransactionOperations transaction;

  @SuppressWarnings("java:S107")
  public ConfirmPlatformAccountPasswordResetService(
      final PlatformVerificationTokenRepository tokens,
      final PlatformAccountRepository accounts,
      final PlatformAccountSessionRevoker sessionRevoker,
      final PasswordHasher hasher,
      final BreachedPasswordChecker breachedPasswordChecker,
      final TransactionOperations transaction) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.sessionRevoker = sessionRevoker;
    this.hasher = hasher;
    this.breachedPasswordChecker = breachedPasswordChecker;
    this.transaction = transaction;
  }

  @Override
  public void handle(final ConfirmPlatformAccountPasswordResetCommand command) {
    if (!PasswordPolicy.isSatisfiedBy(command.newRawPassword())) {
      throw new WeakPasswordException();
    }
    if (breachedPasswordChecker.isBreached(command.newRawPassword())) {
      throw new BreachedPasswordException();
    }

    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final PlatformVerificationToken token =
        tokens.findByTokenHash(presentedHash).orElseThrow(InvalidVerificationTokenException::new);

    if (token.type() != VerificationTokenType.PASSWORD_RESET || !token.isActive()) {
      throw new InvalidVerificationTokenException();
    }

    // ARGON2 OUTSIDE ANY TRANSACTION (TD-PERF-053, as in the tenant tier's ConfirmPasswordReset
    // Service, TD-PERF-052): the hash waits on the Argon2 bulkhead and costs tens of milliseconds,
    // and the pool is ten connections. A value that is not a live reset token never reaches it.
    final String newHash = hasher.hash(command.newRawPassword());

    transaction.executeWithoutResult(status -> persist(token, newHash));
  }

  private void persist(final PlatformVerificationToken token, final String newHash) {
    // The atomic, authoritative check — see this class's own Javadoc.
    if (!tokens.consumeIfActive(token.id(), Instant.now())) {
      throw new InvalidVerificationTokenException();
    }

    final PlatformAccount account =
        accounts
            .findById(token.platformAccountId())
            .orElseThrow(InvalidVerificationTokenException::new);
    account.resetPasswordCredential(newHash);
    accounts.save(account);

    sessionRevoker.revokeAllSessionsFor(account.id());

    LOG.info("event=platform_password_reset_completed platformAccountId={}", account.id());
  }
}
