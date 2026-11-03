package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import com.clavaris.identity.domain.service.TestEmailAddress;
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
 * <p><b>Correctness finding, 2026-10-08:</b> real email delivery must work identically in {@code
 * DEVELOPMENT} and {@code PRODUCTION} Organizations — see {@link TestEmailAddress}'s own Javadoc
 * for why the former {@code OrganizationEnvironment}-wide bypass was wrong (it silently blocked
 * every real registrant's verification email against any sandboxed Organization, which is what
 * every Organization defaults to). Only an address carrying {@link TestEmailAddress}'s own {@code
 * +clavaris_test} marker skips the real send now, regardless of environment — the token is still
 * issued and completable either way (BR-DATA-01: its raw value is still never logged).
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
  private final AccountAuthenticationPolicyProvider policyProvider;

  public RequestEmailVerificationService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final MailSender mailSender,
      final AccountAuthenticationPolicyProvider policyProvider) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
    this.policyProvider = policyProvider;
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

    // ADR-0024 §2: CODE/BOTH issue a second, independently-hashed token carrying the short raw
    // value alongside (or instead of) the original link — same VerificationToken/EMAIL_VERIFICATION
    // type either shape uses, only the raw value's format differs (EmailOneTimeCode vs
    // RefreshTokenSecret). Two rows, not one token trying to satisfy two different raw-value
    // shapes at once, since findByTokenHash's own lookup is by exact hash match.
    final EmailVerificationMethod method =
        policyProvider.policyFor(account.organizationId()).emailVerificationMethod();
    final String rawLinkToken =
        method == EmailVerificationMethod.CODE
            ? null
            : issueToken(account, RefreshTokenSecret.generateRawValue());
    final String rawCode = method == EmailVerificationMethod.LINK ? null : issueCode(account);

    if (TestEmailAddress.isTestAddress(account.email().value())) {
      LOG.info(
          "event=email_verification_bypassed_test_address organizationId={} accountId={}",
          account.organizationId(),
          account.id());
      return;
    }

    if (rawLinkToken != null) {
      mailSender.sendEmailVerification(
          account.email().value(), account.organizationId(), rawLinkToken);
    }
    if (rawCode != null) {
      mailSender.sendEmailVerificationCode(
          account.email().value(), account.organizationId(), rawCode);
    }
  }

  // Hashed with the account, as every typed code is (ADR-0034 addendum): the page that takes it
  // asks for the address too, so nothing is looked up blind across all accounts.
  private String issueCode(final Account account) {
    final String rawCode = EmailOneTimeCode.generate();
    tokens.save(
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            EmailOneTimeCode.hashFor(account.id(), rawCode),
            Instant.now().plus(TOKEN_TTL)));
    return rawCode;
  }

  private String issueToken(final Account account, final String rawValue) {
    final VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawValue),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);
    return rawValue;
  }
}
