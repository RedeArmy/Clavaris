package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RequestEmailVerificationUseCase}. Deliberately NOT
 * {@code @Transactional} end to end, unlike {@code RegisterAccountService}: the token is persisted
 * first (its own, already-committed write) and only then is the mail provider called — holding a
 * database transaction open across a third-party network call would tie up a connection for however
 * long Resend takes to respond, for no correctness benefit (a failed send doesn't need the
 * just-issued token rolled back; the token stays valid either way, and the same request path can be
 * retried).
 *
 * <p><b>SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis):</b> a
 * {@code DEVELOPMENT}-environment Account never triggers a real outbound send — same "no real
 * email/SMS dispatched" behaviour Clerk's own test mode guarantees, here scoped to the whole
 * sandboxed Organization rather than a per-address {@code +clerk_test} convention (this codebase's
 * token-based, not OTP-code-based, verification model has no equivalent short-lived-code channel to
 * hang a per-address convention off of). <b>Named limitation, not silently left unsolved:</b> this
 * bypass avoids burning Resend quota/cost against a sandbox, but does not by itself give a human or
 * an external (out-of-process) test suite a way to retrieve the raw token — the token itself is
 * still real, persisted, and completable by {@code ConfirmEmailVerificationService} exactly as
 * normal, but nothing here logs or otherwise surfaces its raw value (BR-DATA-01: never a credential
 * in a log line, no exception carved out for a sandbox). A real "read it back without a real inbox"
 * channel (mirroring Clerk's own {@code 424242} fixed bypass code) is a separate, larger piece of
 * work, tracked as its own row rather than assumed solved here.
 */
public class RequestEmailVerificationService implements RequestEmailVerificationUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RequestEmailVerificationService.class);

  // No BR pins an exact figure — 24h is a reasonable default for a link a user is expected to
  // follow from their own inbox shortly after registering, not a value load-bearing enough to
  // warrant its own configuration property yet.
  private static final Duration TOKEN_TTL = Duration.ofHours(24);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;

  @SuppressWarnings("PMD.LongVariable")
  private final OrganizationEnvironmentChecker environmentChecker;

  public RequestEmailVerificationService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final MailSender mailSender,
      @SuppressWarnings("PMD.LongVariable")
          final OrganizationEnvironmentChecker environmentChecker) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
    this.environmentChecker = environmentChecker;
  }

  // PMD.GuardLogStatement false positive — same rationale as AuthenticateWithPasswordService's
  // own identical suppression: every logged argument is a cheap accessor, not an expensive
  // computation. PMD.OnlyOneReturn: three genuinely distinct exits (already-verified no-op,
  // DEVELOPMENT-environment bypass, the normal real-send path) — same "one exit per distinct
  // outcome" rationale every admin-API controller in this codebase already applies.
  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.OnlyOneReturn"})
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

    if (environmentChecker.isDevelopment(account.organizationId())) {
      LOG.info(
          "event=email_verification_bypassed_development_environment organizationId={}"
              + " accountId={}",
          account.organizationId(),
          account.id());
      return;
    }

    mailSender.sendEmailVerification(account.email().value(), account.organizationId(), rawToken);
  }
}
