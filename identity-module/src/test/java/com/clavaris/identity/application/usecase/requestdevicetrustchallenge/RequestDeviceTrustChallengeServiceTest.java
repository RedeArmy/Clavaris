package com.clavaris.identity.application.usecase.requestdevicetrustchallenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.codelockout.InMemoryCodeAttemptTracker;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class RequestDeviceTrustChallengeServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private MailSender mailSender;
  private InMemoryCodeAttemptTracker tracker;
  private CodeLockout lockout;
  private RequestDeviceTrustChallengeService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    mailSender = mock(MailSender.class);
    tracker = new InMemoryCodeAttemptTracker(3);
    lockout =
        new CodeLockout(
            tracker,
            accounts,
            mock(MailSender.class),
            mock(AuditEventRecorder.class),
            Runnable::run);
    service =
        new RequestDeviceTrustChallengeService(
            accounts, tokens, mailSender, TransactionOperations.withoutTransaction(), lockout);
  }

  private Account someAccount() {
    return Account.register(organizationId, new Email("device-trust@example.com"));
  }

  @Test
  void issuesADeviceTrustChallengeCodeAndEmailsIt() {
    Account account = someAccount();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestDeviceTrustChallengeCommand(account.id()));

    ArgumentCaptor<VerificationToken> tokenCaptor =
        ArgumentCaptor.forClass(VerificationToken.class);
    verify(tokens).save(tokenCaptor.capture());
    VerificationToken saved = tokenCaptor.getValue();
    assertThat(saved.accountId()).isEqualTo(account.id());
    assertThat(saved.type()).isEqualTo(VerificationTokenType.DEVICE_TRUST_CHALLENGE);
    assertThat(saved.isActive()).isTrue();
    verify(mailSender)
        .sendDeviceTrustChallengeCode(eq(account.email().value()), eq(organizationId), any());
  }

  // Correctness finding, 2026-10-08: no environment check left — only a +clavaris_test-marked
  // address bypasses the real send, regardless of the owning Organization's environment.
  @Test
  void bypassesEmailDeliveryForATestMarkedAddress() {
    Account account =
        Account.register(organizationId, new Email("device-trust+clavaris_test@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestDeviceTrustChallengeCommand(account.id()));

    verify(tokens).save(any());
    verify(mailSender, never()).sendDeviceTrustChallengeCode(any(), any(), any());
  }

  @Test
  void failsDefensivelyWhenTheAccountIsUnresolvable() {
    AccountId accountId = AccountId.newId();
    when(accounts.findById(accountId)).thenReturn(Optional.empty());
    RequestDeviceTrustChallengeCommand command = new RequestDeviceTrustChallengeCommand(accountId);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.handle(command));

    verify(tokens, never()).save(any());
  }

  // ADR-0035: asked on every sign-in, so the mail is the ordinary sign-in code, not a "new device"
  // alert that would be false.
  @Test
  void anEverySignInChallengeIsMailedAsAnOrdinarySignInCode() {
    Account account = someAccount();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(
        new RequestDeviceTrustChallengeCommand(account.id(), ChallengeReason.EVERY_SIGN_IN));

    verify(tokens).save(any());
    verify(mailSender).sendEmailSignInCode(eq(account.email().value()), eq(organizationId), any());
    verify(mailSender, never()).sendDeviceTrustChallengeCode(any(), any(), any());
  }

  // Latest-only: a new code ends the earlier one.
  @Test
  void aNewChallengeEndsTheEarlierOne() {
    Account account = someAccount();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestDeviceTrustChallengeCommand(account.id()));

    verify(tokens)
        .invalidateActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.DEVICE_TRUST_CHALLENGE), any(Instant.class));
  }

  @Test
  void sendsNothingNewWhenAskedAgainWithinTheWait() {
    Account account = someAccount();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(tokens.findLatestActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.DEVICE_TRUST_CHALLENGE), any(Instant.class)))
        .thenReturn(
            Optional.of(
                VerificationToken.issue(
                    account.id(),
                    VerificationTokenType.DEVICE_TRUST_CHALLENGE,
                    "earlier-hash",
                    Instant.now().plusSeconds(600).minusSeconds(10))));

    service.handle(new RequestDeviceTrustChallengeCommand(account.id()));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendDeviceTrustChallengeCode(any(), any(), any());
  }

  // ADR-0033: while the method is paused a code could not be redeemed, so none is issued or mailed.
  @Test
  void sendsNothingWhileTheMethodIsPausedForTheAccount() {
    Account account = someAccount();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    String key = CodeLockout.deviceCodeKey(account.id());
    for (int attempt = 0; attempt < 3; attempt++) {
      lockout.onDeviceCodeFailure(account.id(), key);
    }

    service.handle(
        new RequestDeviceTrustChallengeCommand(account.id(), ChallengeReason.EVERY_SIGN_IN));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendEmailSignInCode(any(), any(), any());
  }
}
