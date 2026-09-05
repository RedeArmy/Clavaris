package com.clavaris.identity.application.usecase.requestemailsignincode;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.OrganizationEnvironmentChecker;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RequestEmailSignInCodeUseCase} — ADR-0024 §3, direct sibling of {@code
 * RequestPasswordResetService}: same silent-on-unknown-account anti-enumeration convention, same
 * {@code DEVELOPMENT}-environment bypass, same transaction-splitting rationale (token persisted
 * first, mail sent after, no DB transaction held open across the network call).
 */
@SuppressWarnings("PMD.LongVariable")
public class RequestEmailSignInCodeService implements RequestEmailSignInCodeUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RequestEmailSignInCodeService.class);

  // Short-lived, same order of magnitude as password-reset's own TTL — a sign-in code is expected
  // to be used within minutes, not browsed to later from an old email.
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;
  private final OrganizationEnvironmentChecker environmentChecker;
  private final AccountAuthenticationPolicyProvider policyProvider;

  @SuppressWarnings("java:S107")
  public RequestEmailSignInCodeService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final MailSender mailSender,
      final OrganizationEnvironmentChecker environmentChecker,
      final AccountAuthenticationPolicyProvider policyProvider) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
    this.environmentChecker = environmentChecker;
    this.policyProvider = policyProvider;
  }

  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.OnlyOneReturn"})
  @Override
  public void handle(final RequestEmailSignInCodeCommand command) {
    if (!policyProvider.policyFor(command.organizationId()).emailCodeSignInEnabled()) {
      throw new EmailCodeSignInNotEnabledException();
    }

    final Account account =
        accounts
            .findByOrganizationIdAndEmail(command.organizationId(), command.email())
            .orElse(null);
    if (account == null) {
      LOG.info(
          "event=email_sign_in_code_requested organizationId={} reason=unknown_account",
          command.organizationId());
      return;
    }

    final String rawCode = EmailOneTimeCode.generate();
    final VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_SIGN_IN_CODE,
            RefreshTokenSecret.hash(rawCode),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);

    if (environmentChecker.isDevelopment(account.organizationId())) {
      LOG.info(
          "event=email_sign_in_code_bypassed_development_environment organizationId={}"
              + " accountId={}",
          command.organizationId(),
          account.id());
      return;
    }

    mailSender.sendEmailSignInCode(account.email().value(), account.organizationId(), rawCode);
    LOG.info(
        "event=email_sign_in_code_requested organizationId={} accountId={}",
        command.organizationId(),
        account.id());
  }
}
