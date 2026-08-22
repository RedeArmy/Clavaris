package com.clavaris.identity.application.usecase.confirmplatformaccountemailverification;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmPlatformAccountEmailVerificationUseCase}. Mirrors {@code
 * confirmemailverification.ConfirmEmailVerificationService} exactly, minus the outbox write — see
 * {@code RegisterPlatformAccountService}'s own Javadoc for why.
 */
public class ConfirmPlatformAccountEmailVerificationService
    implements ConfirmPlatformAccountEmailVerificationUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConfirmPlatformAccountEmailVerificationService.class);

  private final PlatformVerificationTokenRepository tokens;
  private final PlatformAccountRepository accounts;

  public ConfirmPlatformAccountEmailVerificationService(
      final PlatformVerificationTokenRepository tokens, final PlatformAccountRepository accounts) {
    this.tokens = tokens;
    this.accounts = accounts;
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void handle(final ConfirmPlatformAccountEmailVerificationCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final PlatformVerificationToken token =
        tokens.findByTokenHash(presentedHash).orElseThrow(InvalidVerificationTokenException::new);

    if (token.type() != VerificationTokenType.EMAIL_VERIFICATION || !token.isActive()) {
      throw new InvalidVerificationTokenException();
    }

    token.consume();
    tokens.save(token);

    final PlatformAccount account =
        accounts
            .findById(token.platformAccountId())
            .orElseThrow(InvalidVerificationTokenException::new);
    account.verifyEmail();
    accounts.save(account);

    LOG.info("event=platform_account_email_verified platformAccountId={}", account.id());
  }
}
