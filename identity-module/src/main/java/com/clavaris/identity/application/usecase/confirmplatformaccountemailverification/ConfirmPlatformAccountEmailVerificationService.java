package com.clavaris.identity.application.usecase.confirmplatformaccountemailverification;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmPlatformAccountEmailVerificationUseCase}. Mirrors {@code
 * confirmemailverification.ConfirmEmailVerificationService} exactly, minus the outbox write — see
 * {@code RegisterPlatformAccountService}'s own Javadoc for why. Including the SDE-III review,
 * 2026-09-15 TOCTOU fix — see that class's own Javadoc — via {@link
 * PlatformVerificationTokenRepository#consumeIfActive}.
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

    // The atomic, authoritative check — see this class's own Javadoc.
    if (!tokens.consumeIfActive(token.id(), Instant.now())) {
      throw new InvalidVerificationTokenException();
    }

    final PlatformAccount account =
        accounts
            .findById(token.platformAccountId())
            .orElseThrow(InvalidVerificationTokenException::new);
    account.verifyEmail();
    accounts.save(account);

    LOG.info("event=platform_account_email_verified platformAccountId={}", account.id());
  }
}
