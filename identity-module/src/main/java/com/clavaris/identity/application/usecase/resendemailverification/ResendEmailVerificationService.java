package com.clavaris.identity.application.usecase.resendemailverification;

import com.clavaris.identity.application.usecase.codelockout.CodeSignInIdentifier;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationService;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.ResendCooldown;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Orchestration for {@link ResendEmailVerificationUseCase} (TD-FUT-055). Until now the verification
 * email was sent once, at sign-up, and nothing could ask for another: where an Organization
 * requires a verified address at sign-in (BR-ID-17), a person who never got it, or lost it, was
 * stranded.
 *
 * <p>It only decides whether to send and then delegates to {@link RequestEmailVerificationService},
 * so the policy's method (link, code or both), the test-address bypass and the single mail send are
 * not written twice. What it adds is the discipline every other emailed secret here already has:
 * the earlier message stops working when a new one is sent, another one inside {@link
 * ResendCooldown#RESEND_WAIT} sends nothing and leaves the earlier one valid, and the answer does
 * not depend on anything about the Account. The account-dependent work runs on the bounded email
 * pool, after the caller has been answered, so how long it takes says nothing either.
 */
public class ResendEmailVerificationService implements ResendEmailVerificationUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ResendEmailVerificationService.class);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final RequestEmailVerificationUseCase requestEmailVerification;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final Executor emailRequestExecutor;
  private final TransactionOperations transaction;

  public ResendEmailVerificationService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final RequestEmailVerificationUseCase requestEmailVerification,
      final AccountAuthenticationPolicyProvider policyProvider,
      final Executor emailRequestExecutor,
      final TransactionOperations transaction) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.requestEmailVerification = requestEmailVerification;
    this.policyProvider = policyProvider;
    this.emailRequestExecutor = emailRequestExecutor;
    this.transaction = transaction;
  }

  @Override
  public void handle(final ResendEmailVerificationCommand command) {
    final CodeSignInIdentifier identifier =
        CodeSignInIdentifier.of(
            command.rawIdentifier(),
            policyProvider.policyFor(command.organizationId()).usernameSignInEnabled());
    try {
      emailRequestExecutor.execute(() -> resendFor(command, identifier));
    } catch (final RejectedExecutionException _) {
      LOG.warn(
          "event=email_verification_resend_dropped organizationId={} reason=executor_saturated",
          command.organizationId());
    }
  }

  // Runs on the executor's thread, never the request's. Nothing thrown here can reach the caller,
  // and nothing about the outcome may differ in what the caller sees, so a failure is only logged.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void resendFor(
      final ResendEmailVerificationCommand command, final CodeSignInIdentifier identifier) {
    try {
      final Optional<Account> found = identifier.find(accounts, command.organizationId());
      if (found.isEmpty() || !needsAnotherEmail(found.get(), command)) {
        return;
      }
      final Account account = found.get();

      // Asked again inside the wait: send nothing new and keep the earlier message valid, so the
      // person still has the email they already got. Silent, the caller sees the same as always.
      if (ResendCooldown.isActive(
          tokens.findLatestActiveOfTypeForAccount(
              account.id(), VerificationTokenType.EMAIL_VERIFICATION, Instant.now()),
          RequestEmailVerificationService.TOKEN_TTL,
          Instant.now())) {
        LOG.info(
            "event=email_verification_resend_suppressed organizationId={} accountId={}"
                + " reason=resend_cooldown",
            command.organizationId(),
            account.id());
        return;
      }

      // Latest-only: the earlier link and code stop working the moment a new message is on its
      // way, so two live ones never exist at once (several would multiply a guesser's chances).
      transaction.executeWithoutResult(
          status ->
              tokens.invalidateActiveOfTypeForAccount(
                  account.id(), VerificationTokenType.EMAIL_VERIFICATION, Instant.now()));
      requestEmailVerification.handle(new RequestEmailVerificationCommand(account.id()));
      LOG.info(
          "event=email_verification_resent organizationId={} accountId={}",
          command.organizationId(),
          account.id());
    } catch (final RuntimeException failure) {
      LOG.warn(
          "event=email_verification_resend_failed organizationId={} exceptionType={}",
          command.organizationId(),
          failure.getClass().getName());
    }
  }

  // Only an active Account that has not verified its address has anything to be sent: a verified
  // one is done (and RequestEmailVerificationService would do nothing anyway), and a suspended,
  // banned, deleted, rejected or still-pending Account must not be handed a way back in by mail.
  private boolean needsAnotherEmail(
      final Account account, final ResendEmailVerificationCommand command) {
    if (account.status() != AccountStatus.ACTIVE) {
      LOG.info(
          "event=email_verification_resend_suppressed organizationId={} accountId={}"
              + " reason=not_active",
          command.organizationId(),
          account.id());
      return false;
    }
    return account.emailVerifiedAt().isEmpty();
  }
}
