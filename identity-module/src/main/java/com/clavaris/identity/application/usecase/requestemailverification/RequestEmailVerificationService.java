package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;

/**
 * Orchestration for {@link RequestEmailVerificationUseCase}. Deliberately NOT
 * {@code @Transactional} end to end, unlike {@code RegisterAccountService}: the token is persisted
 * first (its own, already-committed write) and only then is the mail provider called — holding a
 * database transaction open across a third-party network call would tie up a connection for however
 * long Resend takes to respond, for no correctness benefit (a failed send doesn't need the
 * just-issued token rolled back; the token stays valid either way, and the same request path can be
 * retried).
 */
public class RequestEmailVerificationService implements RequestEmailVerificationUseCase {

  // No BR pins an exact figure — 24h is a reasonable default for a link a user is expected to
  // follow from their own inbox shortly after registering, not a value load-bearing enough to
  // warrant its own configuration property yet.
  private static final Duration TOKEN_TTL = Duration.ofHours(24);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;

  public RequestEmailVerificationService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final MailSender mailSender) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
  }

  @Override
  public void handle(final RequestEmailVerificationCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new UnknownAccountException(command.accountId()));

    // Idempotent no-op, not an error: a stale "resend" click after the account is already
    // verified must not re-send an email or fail the request (Account.verifyEmail()'s own
    // idempotency comment makes the same call for the confirm side of this flow).
    if (account.emailVerifiedAt().isPresent()) {
      return;
    }

    final String rawToken = RefreshTokenSecret.generateRawValue();
    final VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);

    mailSender.sendEmailVerification(account.email().value(), account.organizationId(), rawToken);
  }
}
