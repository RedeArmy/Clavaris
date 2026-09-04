package com.clavaris.identity.application.usecase.authenticatewithpassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AuthenticateWithPasswordServiceTest {

  private static final String RAW_PASSWORD = "a-correct-password";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("user@example.com");

  private AccountRepository accounts;
  private PasswordVerifier verifier;
  private SecurityMetricsRecorder metrics;
  private AccountAuthenticationPolicyProvider policyProvider;
  private AuthenticateWithPasswordService service;

  // TD-SEC-014: captures what AuthenticateWithPasswordService actually logs, the same way a real
  // log shipper would see it — not a Mockito spy on the Logger, which would prove the call
  // happened but not what the rendered message (with placeholders substituted) actually says.
  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    verifier = mock(PasswordVerifier.class);
    metrics = mock(SecurityMetricsRecorder.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    // Matches today's real default (ADR-0024) — every existing test in this class exercises
    // behaviour that predates the policy gate, so it must stay a no-op unless a test overrides it.
    when(policyProvider.policyFor(organizationId))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    service = new AuthenticateWithPasswordService(accounts, verifier, metrics, policyProvider);

    logAppender.start();
    loggerUnderTest().addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    loggerUnderTest().detachAppender(logAppender);
    logAppender.stop();
    logAppender.list.clear();
  }

  private static Logger loggerUnderTest() {
    return (Logger) LoggerFactory.getLogger(AuthenticateWithPasswordService.class);
  }

  @Test
  void returnsTheAccountIdWhenTheEmailPasswordAndOrganizationAllMatch() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(true);

    AccountId result =
        service.handle(new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD));

    assertThat(result).isEqualTo(account.id());
    assertThat(onlyLoggedMessage())
        .contains("event=login_success")
        .contains(organizationId.toString())
        .contains(account.id().toString());
    // TD-FUT-011: the log line above and this counter must both exist for the same event — proves
    // the metrics wiring is real, not just present in the constructor signature.
    verify(metrics).increment("clavaris.auth.login", "tier", "organization", "outcome", "success");
  }

  @Test
  void rejectsAnUnknownEmail() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));

    verify(verifier, never()).matches(any(), any());
    assertThat(onlyLoggedMessage())
        .contains("event=login_failure")
        .contains("reason=unknown_account");
    verify(metrics)
        .increment(
            "clavaris.auth.login",
            "tier",
            "organization",
            "outcome",
            "failure",
            "reason",
            "unknown_account");
  }

  @Test
  void rejectsAWrongPassword() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(false);
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(onlyLoggedMessage())
        .contains("event=login_failure")
        .contains("reason=invalid_password")
        .contains(account.id().toString());
  }

  @Test
  void rejectsASuspendedAccountEvenWithTheCorrectPassword() {
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.SUSPENDED,
            null,
            null);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));

    // A suspended account must never even reach the password check — the account-status guard
    // runs first, deliberately, not as an afterthought once a credential match already succeeded.
    verify(verifier, never()).matches(any(), any());
    assertThat(onlyLoggedMessage())
        .contains("event=login_failure")
        .contains("reason=inactive_account")
        .contains(account.id().toString());
  }

  @Test
  void rejectsAnAccountWithNoPasswordCredentialAttached() {
    // A social-only account (once SocialIdentity exists) attempting a password login — BR-ID-02
    // guarantees at least one auth method exists, just not necessarily this one.
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(InvalidCredentialsException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(onlyLoggedMessage())
        .contains("event=login_failure")
        .contains("reason=no_password_credential")
        .contains(account.id().toString());
  }

  @Test
  void neverLogsTheRawPasswordOrTheAccountEmailOnAnySuccessOrFailurePath() {
    // BR-DATA-01, exercised across every branch this class has, not just one — the same
    // discipline as RegisterAccountCommandTest/BootstrapPlatformClientCommandTest's toString()
    // guards, applied here to what actually gets written to a real log sink. A future edit that
    // changes a log line to (accidentally) include command.email() or command.rawPassword()
    // fails this test immediately, instead of shipping a PII/credential leak into production logs.
    Account activeAccount = Account.register(organizationId, email);
    activeAccount.attachPasswordCredential("argon2id$stored-hash");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(activeAccount));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(true, false);
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    service.handle(command); // event=login_success
    assertThatExceptionOfType(InvalidCredentialsException.class) // event=login_failure, wrong pwd
        .isThrownBy(() -> service.handle(command));

    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());
    assertThatExceptionOfType(InvalidCredentialsException.class) // event=login_failure, unknown
        .isThrownBy(() -> service.handle(command));

    assertThat(logAppender.list).hasSize(3);
    for (ILoggingEvent event : logAppender.list) {
      assertThat(event.getFormattedMessage())
          .as("no logged line may ever contain the raw password or the account's email")
          .doesNotContain(RAW_PASSWORD)
          .doesNotContain(email.value());
    }
  }

  // ADR-0024 §2/BR-ID-16
  @Test
  void rejectsAnUnverifiedEmailWhenThePolicyRequiresVerificationAtSignIn() {
    Account account =
        Account.reconstitute(
            new AccountId(UUID.randomUUID()),
            organizationId,
            email,
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null);
    account.attachPasswordCredential("argon2id$stored-hash");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(true);
    when(policyProvider.policyFor(organizationId))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                true,
                EmailVerificationMethod.LINK,
                false,
                false,
                false,
                false,
                false,
                true,
                false));
    AuthenticateWithPasswordCommand command =
        new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD);

    assertThatExceptionOfType(EmailNotVerifiedException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(onlyLoggedMessage())
        .contains("event=login_failure")
        .contains("reason=email_not_verified");
  }

  @Test
  void allowsAVerifiedAccountEvenWhenThePolicyRequiresVerificationAtSignIn() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    account.verifyEmail();
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(verifier.matches(RAW_PASSWORD, "argon2id$stored-hash")).thenReturn(true);
    when(policyProvider.policyFor(organizationId))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                true,
                EmailVerificationMethod.LINK,
                false,
                false,
                false,
                false,
                false,
                true,
                false));

    AccountId result =
        service.handle(new AuthenticateWithPasswordCommand(organizationId, email, RAW_PASSWORD));

    assertThat(result).isEqualTo(account.id());
  }

  private String onlyLoggedMessage() {
    List<ILoggingEvent> events = logAppender.list;
    assertThat(events).as("expected exactly one security-event log line").hasSize(1);
    return events.get(0).getFormattedMessage();
  }
}
