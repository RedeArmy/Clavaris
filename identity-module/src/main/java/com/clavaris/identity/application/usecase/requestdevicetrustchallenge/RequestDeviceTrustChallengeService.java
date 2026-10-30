package com.clavaris.identity.application.usecase.requestdevicetrustchallenge;

import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.ResendCooldown;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import com.clavaris.identity.domain.service.TestEmailAddress;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Orchestration for {@link RequestDeviceTrustChallengeUseCase} — ADR-0024 §6. Issued only after the
 * primary factor (password/username/passwordless email) has already succeeded; the caller (one of
 * the four sign-in controllers) already resolved a real, active {@link Account}, so this has no
 * "unknown account" branch to guard, unlike every request-a-code use case in §3. Correctness
 * finding, 2026-10-08: same {@link TestEmailAddress} bypass {@code RequestEmailVerificationService}
 * applies, replacing the former {@code DEVELOPMENT}-environment-wide one.
 */
public class RequestDeviceTrustChallengeService implements RequestDeviceTrustChallengeUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RequestDeviceTrustChallengeService.class);
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;
  private final TransactionOperations transaction;
  private final CodeLockout lockout;

  public RequestDeviceTrustChallengeService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final MailSender mailSender,
      final TransactionOperations transaction,
      final CodeLockout lockout) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
    this.transaction = transaction;
    this.lockout = lockout;
  }

  @Override
  public void handle(final RequestDeviceTrustChallengeCommand command) {
    // Defensive only — every real caller just resolved this exact Account via its own primary
    // factor a moment earlier; an unresolvable id here means the account was deleted in the
    // instant between that check and this call, not a code path a legitimate caller can hit.
    final Account account =
        accounts.findById(command.accountId()).orElseThrow(IllegalStateException::new);

    // Paused after too many wrong codes (ADR-0033): a code could not be redeemed anyway, and
    // sending one would only mail the owner a secret that is refused. The page still shows.
    if (lockout.isLocked(CodeLockout.deviceCodeKey(account.id()))) {
      LOG.info(
          "event=device_trust_challenge_suppressed organizationId={} accountId={} reason=locked",
          account.organizationId(),
          account.id());
      return;
    }

    // Asked again inside the wait: keep the earlier code valid and send nothing new, so the person
    // still has the email they already got (the same wait every other code request keeps).
    if (ResendCooldown.isActive(
        tokens.findLatestActiveOfTypeForAccount(
            account.id(), VerificationTokenType.DEVICE_TRUST_CHALLENGE, Instant.now()),
        TOKEN_TTL,
        Instant.now())) {
      LOG.info(
          "event=device_trust_challenge_suppressed organizationId={} accountId={} reason=resend_cooldown",
          account.organizationId(),
          account.id());
      return;
    }

    final String rawCode = EmailOneTimeCode.generate();
    final VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.DEVICE_TRUST_CHALLENGE,
            EmailOneTimeCode.hashFor(account.id(), rawCode),
            Instant.now().plus(TOKEN_TTL));
    // Latest-only: a new code ends the earlier one, so two live codes never exist at once.
    transaction.executeWithoutResult(
        status -> {
          tokens.invalidateActiveOfTypeForAccount(
              account.id(), VerificationTokenType.DEVICE_TRUST_CHALLENGE, Instant.now());
          tokens.save(token);
        });

    if (TestEmailAddress.isTestAddress(account.email().value())) {
      LOG.info(
          "event=device_trust_challenge_bypassed_test_address organizationId={} accountId={}",
          account.organizationId(),
          account.id());
      return;
    }

    if (command.reason() == ChallengeReason.EVERY_SIGN_IN) {
      // ADR-0035: not a new-device alert, an ordinary sign-in code — it is asked for on every
      // sign-in, so the email must not claim the device is unfamiliar.
      mailSender.sendEmailSignInCode(account.email().value(), account.organizationId(), rawCode);
    } else {
      mailSender.sendDeviceTrustChallengeCode(
          account.email().value(), account.organizationId(), rawCode);
    }
    LOG.info(
        "event=device_trust_challenge_requested organizationId={} accountId={}",
        account.organizationId(),
        account.id());
  }
}
