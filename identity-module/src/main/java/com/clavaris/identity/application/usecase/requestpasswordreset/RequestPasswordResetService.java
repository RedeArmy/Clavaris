package com.clavaris.identity.application.usecase.requestpasswordreset;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.event.PasswordResetRequestedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RequestPasswordResetUseCase}. Same transaction-splitting rationale as
 * {@code RequestEmailVerificationService}: the token is persisted first, the mail provider is
 * called after, no database transaction held open across the network call.
 *
 * <p>TD-FUT-009: logs a structured {@code event=} line on both paths, extending the exact {@code
 * AuthenticateWithPasswordService} convention (opaque IDs only, never the email/token) — the one
 * event {@code nfr-quality-attributes.md} §5 named that TD-SEC-014/016/017 couldn't close yet
 * because this feature didn't exist. The unknown-account path still logs (organizationId only, no
 * accountId to log) — an internal log line revealing "no such account" is not the same anti-
 * enumeration concern as the HTTP response revealing it; operators seeing repeated unknown-account
 * attempts against one Organization is exactly BR-ID-06's rate-limiting signal.
 */
public class RequestPasswordResetService implements RequestPasswordResetUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RequestPasswordResetService.class);

  // Shorter than the email-verification TTL on purpose: a password-reset link is a materially more
  // sensitive credential-recovery capability, and is expected to be used within minutes of request,
  // not browsed to a day later from an old email.
  private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;
  private final EventOutboxWriter outbox;

  public RequestPasswordResetService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final MailSender mailSender,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
    this.outbox = outbox;
  }

  // PMD.GuardLogStatement false positive — same rationale as AuthenticateWithPasswordService's
  // own identical suppression: every logged argument is a direct value-object accessor, not an
  // expensive computation.
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  public void handle(final RequestPasswordResetCommand command) {
    // No account found: return normally, exactly as the found-and-sent path does — see this
    // interface's own Javadoc for why (user-enumeration prevention, not a missed edge case).
    final Account account =
        accounts
            .findByOrganizationIdAndEmail(command.organizationId(), command.email())
            .orElse(null);
    if (account == null) {
      LOG.info(
          "event=password_reset_requested organizationId={} reason=unknown_account",
          command.organizationId());
      return;
    }

    final String rawToken = RefreshTokenSecret.generateRawValue();
    final VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);
    outbox.write(
        "password_reset.requested", account.id(), PasswordResetRequestedEvent.from(account));

    mailSender.sendPasswordReset(account.email().value(), account.organizationId(), rawToken);
    LOG.info(
        "event=password_reset_requested organizationId={} accountId={}",
        command.organizationId(),
        account.id());
  }
}
