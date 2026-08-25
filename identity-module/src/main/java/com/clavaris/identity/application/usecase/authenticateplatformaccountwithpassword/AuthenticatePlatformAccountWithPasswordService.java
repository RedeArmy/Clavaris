package com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformPasswordCredential;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link AuthenticatePlatformAccountWithPasswordUseCase}. Mirrors {@code
 * authenticatewithpassword.AuthenticateWithPasswordService} exactly (same anti-enumeration
 * indistinguishable-failure shape, same {@code PasswordVerifier} port reused as-is), minus {@code
 * organizationId} scoping — a {@code PlatformAccount}'s email is globally unique.
 */
public class AuthenticatePlatformAccountWithPasswordService
    implements AuthenticatePlatformAccountWithPasswordUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(AuthenticatePlatformAccountWithPasswordService.class);

  // PMD.AvoidDuplicateLiterals: same rationale as AuthenticateWithPasswordService's own identical
  // constant — one metric family repeated across every branch, not accidental duplication.
  private static final String LOGIN_METRIC = "clavaris.auth.login";

  private final PlatformAccountRepository accounts;
  private final PasswordVerifier verifier;
  private final SecurityMetricsRecorder metrics;

  public AuthenticatePlatformAccountWithPasswordService(
      final PlatformAccountRepository accounts,
      final PasswordVerifier verifier,
      final SecurityMetricsRecorder metrics) {
    this.accounts = accounts;
    this.verifier = verifier;
    this.metrics = metrics;
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  public PlatformAccountId handle(final AuthenticatePlatformAccountWithPasswordCommand command) {
    final Optional<PlatformAccount> found = accounts.findByEmail(command.email());
    if (found.isEmpty()) {
      LOG.info("event=platform_login_failure reason=unknown_account");
      recordFailure("unknown_account");
      throw new InvalidPlatformCredentialsException();
    }
    final PlatformAccount account = found.get();

    if (account.status() != AccountStatus.ACTIVE) {
      LOG.info(
          "event=platform_login_failure platformAccountId={} reason=inactive_account",
          account.id());
      recordFailure("inactive_account");
      throw new InvalidPlatformCredentialsException();
    }

    final Optional<PlatformPasswordCredential> credential = account.passwordCredential();
    if (credential.isEmpty()) {
      LOG.info(
          "event=platform_login_failure platformAccountId={} reason=no_password_credential",
          account.id());
      recordFailure("no_password_credential");
      throw new InvalidPlatformCredentialsException();
    }

    if (!verifier.matches(command.rawPassword(), credential.get().passwordHash())) {
      LOG.info(
          "event=platform_login_failure platformAccountId={} reason=invalid_password",
          account.id());
      recordFailure("invalid_password");
      throw new InvalidPlatformCredentialsException();
    }

    LOG.info("event=platform_login_success platformAccountId={}", account.id());
    metrics.increment(LOGIN_METRIC, "tier", "platform", "outcome", "success");
    return account.id();
  }

  private void recordFailure(final String reason) {
    metrics.increment(LOGIN_METRIC, "tier", "platform", "outcome", "failure", "reason", reason);
  }
}
