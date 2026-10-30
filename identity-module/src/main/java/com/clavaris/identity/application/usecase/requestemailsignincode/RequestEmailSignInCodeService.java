package com.clavaris.identity.application.usecase.requestemailsignincode;

import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Orchestration for {@link RequestEmailSignInCodeUseCase} — ADR-0024 §3, direct sibling of {@code
 * RequestPasswordResetService}: same silent-on-unknown-account anti-enumeration convention, same
 * {@link TestEmailAddress} bypass.
 *
 * <p><b>The response never depends on whether the account exists.</b> Everything that does — the
 * account lookup, issuing the token, the mail — runs on {@code emailRequestExecutor}, so the caller
 * gets back after the policy check alone whether or not there was anything to do. Before, an
 * unknown address returned straight after the lookup while a known one waited for the token insert
 * and a network call to the mail provider: an observable difference in response time that told an
 * attacker which addresses have an account, however identical the redirect looked. A task the
 * executor rejects (its queue is bounded, TD-PERF-040's reasoning) is logged and dropped, equally
 * for known and unknown addresses; the person asks again.
 *
 * <p>Issuing a code ends the Account's earlier ones of the same type, so only the latest is ever
 * valid: several live secrets at once multiply a guesser's chances, and SP 800-63B-4 §3.1.3.2 does
 * not let a new secret reset the failed-attempt count either. Only this type is ended, never a
 * password reset or email verification the person has under way. Asking again within {@link
 * ResendCooldown#WAIT} sends nothing new and leaves the earlier secret valid. The token is written
 * in its own transaction and the mail sent after it, so no connection is held across the network
 * call.
 */
public class RequestEmailSignInCodeService implements RequestEmailSignInCodeUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RequestEmailSignInCodeService.class);

  // Short-lived, same order of magnitude as password-reset's own TTL — a sign-in code is expected
  // to be used within minutes, not browsed to later from an old email.
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final MailSender mailSender;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final Executor emailRequestExecutor;
  private final TransactionOperations transaction;
  private final CodeLockout lockout;

  // java:S107: one parameter per collaborating port, as every orchestration service here.
  @SuppressWarnings("java:S107")
  public RequestEmailSignInCodeService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final MailSender mailSender,
      final AccountAuthenticationPolicyProvider policyProvider,
      final Executor emailRequestExecutor,
      final TransactionOperations transaction,
      final CodeLockout lockout) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mailSender = mailSender;
    this.policyProvider = policyProvider;
    this.emailRequestExecutor = emailRequestExecutor;
    this.transaction = transaction;
    this.lockout = lockout;
  }

  @Override
  public void handle(final RequestEmailSignInCodeCommand command) {
    if (!policyProvider.policyFor(command.organizationId()).emailCodeSignInEnabled()) {
      throw new EmailCodeSignInNotEnabledException();
    }
    try {
      emailRequestExecutor.execute(() -> issueFor(command));
    } catch (final RejectedExecutionException _) {
      LOG.warn(
          "event=email_sign_in_code_dropped organizationId={} reason=executor_saturated",
          command.organizationId());
    }
  }

  // Runs on the executor's thread, never the request's. Nothing thrown here can reach the caller,
  // and nothing about the outcome may differ in what the caller sees, so a failure is only logged.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void issueFor(final RequestEmailSignInCodeCommand command) {
    try {
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

      // The method is paused for this address (ADR-0033): a code could not be used anyway, and
      // sending one would only mail the owner a secret they cannot redeem. Silent, like the wait.
      if (lockout.isLocked(CodeLockout.signInCodeKey(command.organizationId(), command.email()))) {
        LOG.info(
            "event=email_sign_in_code_suppressed organizationId={} accountId={} reason=locked",
            command.organizationId(),
            account.id());
        return;
      }

      // Asked again inside the wait: send nothing new and keep the earlier secret valid, so the
      // person still has the email they already got. Silent, the caller sees the same as always.
      if (ResendCooldown.isActive(
          tokens.findLatestActiveOfTypeForAccount(
              account.id(), VerificationTokenType.EMAIL_SIGN_IN_CODE, Instant.now()),
          TOKEN_TTL,
          Instant.now())) {
        LOG.info(
            "event=email_sign_in_code_suppressed organizationId={} accountId={} reason=resend_cooldown",
            command.organizationId(),
            account.id());
        return;
      }

      final String rawCode = EmailOneTimeCode.generate();
      final VerificationToken token =
          VerificationToken.issue(
              account.id(),
              VerificationTokenType.EMAIL_SIGN_IN_CODE,
              EmailOneTimeCode.hashFor(account.id(), rawCode),
              Instant.now().plus(TOKEN_TTL));
      transaction.executeWithoutResult(
          status -> {
            tokens.invalidateActiveOfTypeForAccount(
                account.id(), VerificationTokenType.EMAIL_SIGN_IN_CODE, Instant.now());
            tokens.save(token);
          });

      if (TestEmailAddress.isTestAddress(account.email().value())) {
        LOG.info(
            "event=email_sign_in_code_bypassed_test_address organizationId={} accountId={}",
            command.organizationId(),
            account.id());
        return;
      }

      mailSender.sendEmailSignInCode(account.email().value(), account.organizationId(), rawCode);
      LOG.info(
          "event=email_sign_in_code_requested organizationId={} accountId={}",
          command.organizationId(),
          account.id());
    } catch (final RuntimeException failure) {
      LOG.warn(
          "event=email_sign_in_code_failed organizationId={} exceptionType={}",
          command.organizationId(),
          failure.getClass().getName());
    }
  }
}
