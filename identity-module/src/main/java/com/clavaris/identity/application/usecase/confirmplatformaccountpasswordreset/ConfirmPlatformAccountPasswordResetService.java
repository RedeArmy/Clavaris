package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.PasswordPolicy;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmPlatformAccountPasswordResetUseCase}. ADR-0012's equivalent of
 * BR-ID-04: a successful reset revokes every {@code HttpSession} Spring Security's {@code
 * SessionRegistry} knows about for this account (via {@link PlatformAccountSessionRevoker}), not a
 * refresh-token cascade — see that port's own Javadoc.
 */
public class ConfirmPlatformAccountPasswordResetService
    implements ConfirmPlatformAccountPasswordResetUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConfirmPlatformAccountPasswordResetService.class);

  private final PlatformVerificationTokenRepository tokens;
  private final PlatformAccountRepository accounts;
  private final PlatformAccountSessionRevoker sessionRevoker;
  private final PasswordHasher hasher;

  public ConfirmPlatformAccountPasswordResetService(
      final PlatformVerificationTokenRepository tokens,
      final PlatformAccountRepository accounts,
      final PlatformAccountSessionRevoker sessionRevoker,
      final PasswordHasher hasher) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.sessionRevoker = sessionRevoker;
    this.hasher = hasher;
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void handle(final ConfirmPlatformAccountPasswordResetCommand command) {
    if (!PasswordPolicy.isSatisfiedBy(command.newRawPassword())) {
      throw new WeakPasswordException();
    }

    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final PlatformVerificationToken token =
        tokens.findByTokenHash(presentedHash).orElseThrow(InvalidVerificationTokenException::new);

    if (token.type() != VerificationTokenType.PASSWORD_RESET || !token.isActive()) {
      throw new InvalidVerificationTokenException();
    }

    token.consume();
    tokens.save(token);

    final PlatformAccount account =
        accounts
            .findById(token.platformAccountId())
            .orElseThrow(InvalidVerificationTokenException::new);
    account.resetPasswordCredential(hasher.hash(command.newRawPassword()));
    accounts.save(account);

    sessionRevoker.revokeAllSessionsFor(account.id());

    LOG.info("event=platform_password_reset_completed platformAccountId={}", account.id());
  }
}
