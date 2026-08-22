package com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RequestPlatformAccountPasswordResetUseCase}. Mirrors {@code
 * requestpasswordreset.RequestPasswordResetService} exactly (same transaction-splitting rationale,
 * same anti-enumeration no-op-on-not-found shape), minus the outbox write.
 */
public class RequestPlatformAccountPasswordResetService
    implements RequestPlatformAccountPasswordResetUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RequestPlatformAccountPasswordResetService.class);

  // Same shorter-than-verification TTL rationale as
  // requestpasswordreset.RequestPasswordResetService.
  private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

  private final PlatformAccountRepository accounts;
  private final PlatformVerificationTokenRepository tokens;
  private final PlatformMailSender mailSender;

  public RequestPlatformAccountPasswordResetService(
      final PlatformAccountRepository accounts,
      final PlatformVerificationTokenRepository tokens,
      final PlatformMailSender mailSender) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  public void handle(final RequestPlatformAccountPasswordResetCommand command) {
    final PlatformAccount account = accounts.findByEmail(command.email()).orElse(null);
    if (account == null) {
      LOG.info("event=platform_password_reset_requested reason=unknown_account");
      return;
    }

    final String rawToken = RefreshTokenSecret.generateRawValue();
    final PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);

    mailSender.sendPlatformAccountPasswordReset(account.email().value(), rawToken);
    LOG.info("event=platform_password_reset_requested platformAccountId={}", account.id());
  }
}
