package com.clavaris.identity.application.usecase.authenticatewithpassword;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.PasswordCredential;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link AuthenticateWithPasswordUseCase}. Every rejection path below throws the
 * exact same {@link InvalidCredentialsException}, with no distinguishing detail — unknown email, a
 * non-{@code ACTIVE} account, an account with no password credential (a social-only account
 * attempting a password login), and a genuinely wrong password are one outcome from the caller's
 * point of view, deliberately, to close the username-enumeration side channel a differentiated
 * response would open.
 *
 * <p>TD-SEC-014 / {@code nfr-quality-attributes.md} §5: every path below also logs a structured,
 * grep-able security event before returning or throwing — until this class, nothing in the
 * identity/auth surface logged anything at all, leaving an incident investigation with no signal.
 * The failure log lines deliberately carry more detail server-side (which specific reason, {@code
 * accountId} when known) than {@link InvalidCredentialsException} ever exposes to the caller — the
 * anti-enumeration property above is about the HTTP response, not about blinding operators to
 * what's actually happening. What never appears in any of these lines, on any path: the raw
 * password, the account's email (BR-DATA-01 — email is PII), or the password hash. Plain key=value
 * text for now, not yet the JSON format `nfr-quality-attributes.md` §5 ultimately calls for — that
 * depends on an observability stack (JSON encoder, log shipper) not chosen yet; these lines upgrade
 * to real structured JSON once that lands, without changing what they say.
 */
public class AuthenticateWithPasswordService implements AuthenticateWithPasswordUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticateWithPasswordService.class);

  // PMD.AvoidDuplicateLiterals: the metric name/tag keys below are inherently repeated across
  // every branch of this method (one metric family, several outcomes) — extracted into constants
  // and the two recordX() helpers below, not repeated string literals PMD is right to flag.
  private static final String LOGIN_METRIC = "clavaris.auth.login";

  private final AccountRepository accounts;
  private final PasswordVerifier verifier;
  private final SecurityMetricsRecorder metrics;

  public AuthenticateWithPasswordService(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final SecurityMetricsRecorder metrics) {
    this.accounts = accounts;
    this.verifier = verifier;
    this.metrics = metrics;
  }

  // PMD.GuardLogStatement false positive: every logged argument below is a direct value-object
  // accessor (organizationId()/id() on an already-in-memory record/UUID wrapper) — not an
  // expensive computation the INFO level should be checked before evaluating, the actual concern
  // the rule exists to catch. Guarding these with isInfoEnabled() would be noise, not safety.
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  public AccountId handle(final AuthenticateWithPasswordCommand command) {
    final Optional<Account> found =
        accounts.findByOrganizationIdAndEmail(command.organizationId(), command.email());
    if (found.isEmpty()) {
      // No accountId to log — by definition, none was found. organizationId alone is still a
      // real signal: repeated unknown-account attempts against one Organization is exactly the
      // credential-stuffing/enumeration pattern BR-ID-06's rate limiting will need this log line
      // to detect once it exists.
      LOG.info(
          "event=login_failure organizationId={} reason=unknown_account", command.organizationId());
      recordFailure("unknown_account");
      throw new InvalidCredentialsException();
    }
    final Account account = found.get();

    // A suspended/deactivated account must never authenticate, even with the exactly-correct
    // password — checked before touching the password hash at all, not as an afterthought once a
    // credential match already succeeded.
    if (account.status() != AccountStatus.ACTIVE) {
      LOG.info(
          "event=login_failure organizationId={} accountId={} reason=inactive_account",
          command.organizationId(),
          account.id());
      recordFailure("inactive_account");
      throw new InvalidCredentialsException();
    }

    final Optional<PasswordCredential> credential = account.passwordCredential();
    if (credential.isEmpty()) {
      LOG.info(
          "event=login_failure organizationId={} accountId={} reason=no_password_credential",
          command.organizationId(),
          account.id());
      recordFailure("no_password_credential");
      throw new InvalidCredentialsException();
    }

    if (!verifier.matches(command.rawPassword(), credential.get().passwordHash())) {
      LOG.info(
          "event=login_failure organizationId={} accountId={} reason=invalid_password",
          command.organizationId(),
          account.id());
      recordFailure("invalid_password");
      throw new InvalidCredentialsException();
    }

    LOG.info(
        "event=login_success organizationId={} accountId={}",
        command.organizationId(),
        account.id());
    metrics.increment(LOGIN_METRIC, "tier", "organization", "outcome", "success");
    return account.id();
  }

  private void recordFailure(final String reason) {
    metrics.increment(LOGIN_METRIC, "tier", "organization", "outcome", "failure", "reason", reason);
  }
}
