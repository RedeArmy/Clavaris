package com.clavaris.identity.application.usecase.authenticatewithusername;

import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.PasswordCredential;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link AuthenticateWithUsernameUseCase} — ADR-0024 §4, a structural twin of
 * {@code AuthenticateWithPasswordService} (same anti-enumeration collapsing, same reused {@link
 * InvalidCredentialsException}/{@link EmailNotVerifiedException}), differing only in the lookup key
 * (username instead of email) and reusing the same {@link PasswordVerifier} port — the factor is
 * still "password," {@code amr=pwd} either way, which is why this calls the plain {@code
 * AuthenticatedSessionEstablisher#establish} method, not a new one.
 *
 * <p>BR-ID-22 (SDE-III review, 2026-09-15): shares {@code AuthenticateWithPasswordService}'s own
 * timing-side-channel fix too — see that class's own Javadoc addendum and {@link
 * PasswordVerifier#payVerificationCostRegardlessOfOutcome}. Being a "structural twin" meant this
 * class carried the exact same gap: {@code unknown_username} and {@code inactive_account} returned
 * without ever calling {@link PasswordVerifier#matches}.
 */
public class AuthenticateWithUsernameService implements AuthenticateWithUsernameUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticateWithUsernameService.class);

  private final AccountRepository accounts;
  private final PasswordVerifier verifier;
  private final AccountAuthenticationPolicyProvider policyProvider;

  public AuthenticateWithUsernameService(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final AccountAuthenticationPolicyProvider policyProvider) {
    this.accounts = accounts;
    this.verifier = verifier;
    this.policyProvider = policyProvider;
  }

  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.CyclomaticComplexity"})
  @Override
  public Account handle(final AuthenticateWithUsernameCommand command) {
    final Optional<Account> found =
        accounts.findByOrganizationIdAndUsername(command.organizationId(), command.username());
    if (found.isEmpty()) {
      // BR-ID-22: see this class's own Javadoc addendum.
      verifier.payVerificationCostRegardlessOfOutcome(command.rawPassword());
      LOG.info(
          "event=login_failure organizationId={} reason=unknown_username",
          command.organizationId());
      throw new InvalidCredentialsException();
    }
    final Account account = found.get();

    if (account.status() != AccountStatus.ACTIVE) {
      // BR-ID-22: see this class's own Javadoc addendum.
      verifier.payVerificationCostRegardlessOfOutcome(command.rawPassword());
      LOG.info(
          "event=login_failure organizationId={} accountId={} reason=inactive_account",
          command.organizationId(),
          account.id());
      throw new InvalidCredentialsException();
    }

    final Optional<PasswordCredential> credential = account.passwordCredential();
    // BR-ID-22: credential.isEmpty() must still pay the same Argon2id cost matches() below would —
    // written as two explicit branches, not the original single "||" short-circuit, precisely so
    // credential.isEmpty() can no longer skip that cost the way "||" always would.
    if (credential.isEmpty()) {
      verifier.payVerificationCostRegardlessOfOutcome(command.rawPassword());
      LOG.info(
          "event=login_failure organizationId={} accountId={} reason=invalid_password",
          command.organizationId(),
          account.id());
      throw new InvalidCredentialsException();
    }
    if (!verifier.matches(command.rawPassword(), credential.get().passwordHash())) {
      LOG.info(
          "event=login_failure organizationId={} accountId={} reason=invalid_password",
          command.organizationId(),
          account.id());
      throw new InvalidCredentialsException();
    }

    if (policyProvider.policyFor(account.organizationId()).emailVerificationRequiredAtSignIn()
        && account.emailVerifiedAt().isEmpty()) {
      LOG.info(
          "event=login_failure organizationId={} accountId={} reason=email_not_verified",
          command.organizationId(),
          account.id());
      throw new EmailNotVerifiedException();
    }

    LOG.info(
        "event=login_success organizationId={} accountId={}",
        command.organizationId(),
        account.id());
    return account;
  }
}
