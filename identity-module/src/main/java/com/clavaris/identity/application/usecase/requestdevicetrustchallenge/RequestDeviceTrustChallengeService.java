package com.clavaris.identity.application.usecase.requestdevicetrustchallenge;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
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
 * Orchestration for {@link RequestDeviceTrustChallengeUseCase} — ADR-0024 §6. Issued only after the
 * primary factor (password/username/passwordless email) has already succeeded; the caller (one of
 * the four sign-in controllers) already resolved a real, active {@link Account}, so this has no
 * "unknown account" branch to guard, unlike every request-a-code use case in §3.
 */
public class RequestDeviceTrustChallengeService implements RequestDeviceTrustChallengeUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RequestDeviceTrustChallengeService.class);
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;

  @SuppressWarnings("PMD.LongVariable")
  private final OrganizationEnvironmentChecker environmentChecker;

  public RequestDeviceTrustChallengeService(
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

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  public void handle(final RequestDeviceTrustChallengeCommand command) {
    // Defensive only — every real caller just resolved this exact Account via its own primary
    // factor a moment earlier; an unresolvable id here means the account was deleted in the
    // instant between that check and this call, not a code path a legitimate caller can hit.
    final Account account =
        accounts.findById(command.accountId()).orElseThrow(IllegalStateException::new);

    final String rawCode = EmailOneTimeCode.generate();
    final VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.DEVICE_TRUST_CHALLENGE,
            RefreshTokenSecret.hash(rawCode),
            Instant.now().plus(TOKEN_TTL));
    tokens.save(token);

    if (environmentChecker.isDevelopment(account.organizationId())) {
      LOG.info(
          "event=device_trust_challenge_bypassed_development_environment organizationId={}"
              + " accountId={}",
          account.organizationId(),
          account.id());
      return;
    }

    mailSender.sendDeviceTrustChallengeCode(
        account.email().value(), account.organizationId(), rawCode);
    LOG.info(
        "event=device_trust_challenge_requested organizationId={} accountId={}",
        account.organizationId(),
        account.id());
  }
}
