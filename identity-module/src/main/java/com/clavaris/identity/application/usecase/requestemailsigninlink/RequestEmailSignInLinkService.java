package com.clavaris.identity.application.usecase.requestemailsigninlink;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.OrganizationEnvironmentChecker;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RequestEmailSignInLinkUseCase} — ADR-0024 §3, the link counterpart to
 * {@code RequestEmailSignInCodeService}; see that class's own Javadoc for the shared conventions
 * (silent-on-unknown-account, {@code DEVELOPMENT}-bypass, split transaction).
 */
@SuppressWarnings("PMD.LongVariable")
public class RequestEmailSignInLinkService implements RequestEmailSignInLinkUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RequestEmailSignInLinkService.class);
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;
  private final OrganizationEnvironmentChecker environmentChecker;
  private final AccountAuthenticationPolicyProvider policyProvider;

  @SuppressWarnings("java:S107")
  public RequestEmailSignInLinkService(
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
  public void handle(final RequestEmailSignInLinkCommand command) {
    if (!policyProvider.policyFor(command.organizationId()).emailLinkSignInEnabled()) {
      throw new EmailLinkSignInNotEnabledException();
    }

    final Account account =
        accounts
            .findByOrganizationIdAndEmail(command.organizationId(), command.email())
            .orElse(null);
    if (account == null) {
      LOG.info(
          "event=email_sign_in_link_requested organizationId={} reason=unknown_account",
          command.organizationId());
      return;
    }

    final String rawToken = RefreshTokenSecret.generateRawValue();
    final VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_SIGN_IN_LINK,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);

    if (environmentChecker.isDevelopment(account.organizationId())) {
      LOG.info(
          "event=email_sign_in_link_bypassed_development_environment organizationId={}"
              + " accountId={}",
          command.organizationId(),
          account.id());
      return;
    }

    mailSender.sendEmailSignInLink(account.email().value(), account.organizationId(), rawToken);
    LOG.info(
        "event=email_sign_in_link_requested organizationId={} accountId={}",
        command.organizationId(),
        account.id());
  }
}
