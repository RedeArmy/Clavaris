package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;

/**
 * Orchestration for {@link RequestPlatformAccountEmailVerificationUseCase}. Same
 * transaction-splitting rationale as {@code
 * requestemailverification.RequestEmailVerificationService} — token persisted first, mail provider
 * called after, no DB transaction held across the network call.
 */
public class RequestPlatformAccountEmailVerificationService
    implements RequestPlatformAccountEmailVerificationUseCase {

  private static final Duration TOKEN_TTL = Duration.ofHours(24);

  private final PlatformAccountRepository accounts;
  private final PlatformVerificationTokenRepository tokens;
  private final PlatformMailSender mailSender;

  public RequestPlatformAccountEmailVerificationService(
      final PlatformAccountRepository accounts,
      final PlatformVerificationTokenRepository tokens,
      final PlatformMailSender mailSender) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
  }

  @Override
  public void handle(final RequestPlatformAccountEmailVerificationCommand command) {
    final PlatformAccount account =
        accounts
            .findById(command.platformAccountId())
            .orElseThrow(() -> new UnknownPlatformAccountException(command.platformAccountId()));

    if (account.emailVerifiedAt().isPresent()) {
      return;
    }

    final String rawToken = RefreshTokenSecret.generateRawValue();
    final PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);

    mailSender.sendPlatformAccountEmailVerification(account.email().value(), rawToken);
  }
}
