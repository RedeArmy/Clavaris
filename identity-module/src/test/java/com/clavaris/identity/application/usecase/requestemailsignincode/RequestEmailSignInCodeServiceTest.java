package com.clavaris.identity.application.usecase.requestemailsignincode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.codelockout.InMemoryCodeAttemptTracker;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
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
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

class RequestEmailSignInCodeServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private MailSender mailSender;
  private AccountAuthenticationPolicyProvider policyProvider;
  private InMemoryCodeAttemptTracker tracker;
  private CodeLockout lockout;
  private RequestEmailSignInCodeService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    mailSender = mock(MailSender.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    tracker = new InMemoryCodeAttemptTracker(3);
    lockout =
        new CodeLockout(
            tracker,
            accounts,
            mock(MailSender.class),
            mock(AuditEventRecorder.class),
            Runnable::run);
    service =
        new RequestEmailSignInCodeService(
            accounts,
            tokens,
            mailSender,
            policyProvider,
            Runnable::run,
            TransactionOperations.withoutTransaction(),
            lockout);
    when(policyProvider.policyFor(organizationId)).thenReturn(enabledPolicy());
  }

  private static AccountAuthenticationPolicySnapshot enabledPolicy() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, true, false, false, false, true, false, false);
  }

  @Test
  void issuesACodeTokenAndEmailsItWhenTheAccountExists() {
    Email email = new Email("code-flow@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    ArgumentCaptor<VerificationToken> tokenCaptor =
        ArgumentCaptor.forClass(VerificationToken.class);
    verify(tokens).save(tokenCaptor.capture());
    assertThat(tokenCaptor.getValue().accountId()).isEqualTo(account.id());
    assertThat(tokenCaptor.getValue().isActive()).isTrue();
    verify(mailSender).sendEmailSignInCode(eq(email.value()), eq(organizationId), any());
  }

  @Test
  void silentlyNoOpsForAnUnknownAccountAntiEnumeration() {
    Email email = new Email("nobody@example.com");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendEmailSignInCode(any(), any(), any());
  }

  // Correctness finding, 2026-10-08: no environment check left — only a +clavaris_test-marked
  // address bypasses the real send, regardless of the owning Organization's environment.
  @Test
  void bypassesEmailDeliveryForATestMarkedAddress() {
    Email email = new Email("dev-org+clavaris_test@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(tokens).save(any());
    verify(mailSender, never()).sendEmailSignInCode(any(), any(), any());
  }

  @Test
  void rejectsWhenTheOrganizationHasNotEnabledEmailCodeSignIn() {
    when(policyProvider.policyFor(organizationId))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    Email email = new Email("someone@example.com");
    RequestEmailSignInCodeCommand command =
        new RequestEmailSignInCodeCommand(organizationId, email);

    assertThatExceptionOfType(EmailCodeSignInNotEnabledException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).findByOrganizationIdAndEmail(any(), any());
  }

  // The earlier secret must stop working the moment a new one is issued: several live at once
  // multiply a guesser's chances. Ended before the new one is written, and only this type.
  @Test
  void endsTheEarlierSecretsOfTheSameTypeBeforeWritingTheNewOne() {
    Email email = new Email("reissue@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    InOrder order = inOrder(tokens);
    order
        .verify(tokens)
        .invalidateActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.EMAIL_SIGN_IN_CODE), any(Instant.class));
    order.verify(tokens).save(any());
    verify(tokens, never()).invalidateAllActiveForAccount(any(), any());
  }

  // Whether the caller learns anything from the outcome: nothing is thrown for an unknown address,
  // for a failure while issuing, or for a saturated executor.
  @Test
  void aFailureWhileIssuingNeverReachesTheCaller() {
    Email email = new Email("boom@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(tokens).save(any());

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(mailSender, never()).sendEmailSignInCode(any(), any(), any());
  }

  @Test
  void aSaturatedExecutorIsLoggedAndDroppedNotThrown() {
    Email email = new Email("busy@example.com");
    RequestEmailSignInCodeService saturated =
        new RequestEmailSignInCodeService(
            accounts,
            tokens,
            mailSender,
            policyProvider,
            task -> {
              throw new java.util.concurrent.RejectedExecutionException("queue full");
            },
            TransactionOperations.withoutTransaction(),
            lockout);

    saturated.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(accounts, never()).findByOrganizationIdAndEmail(any(), any());
    verify(tokens, never()).save(any());
  }

  // The point of running the work on the executor: the caller returns before any of it, so the
  // response time cannot depend on whether the address has an account.
  @Test
  void theCallerReturnsBeforeTheAccountLookupWhenTheWorkIsDeferred() {
    java.util.List<Runnable> deferred = new java.util.ArrayList<>();
    RequestEmailSignInCodeService deferring =
        new RequestEmailSignInCodeService(
            accounts,
            tokens,
            mailSender,
            policyProvider,
            deferred::add,
            TransactionOperations.withoutTransaction(),
            lockout);

    deferring.handle(
        new RequestEmailSignInCodeCommand(organizationId, new Email("later@example.com")));

    org.mockito.Mockito.verifyNoInteractions(accounts, tokens, mailSender);
    org.assertj.core.api.Assertions.assertThat(deferred).hasSize(1);
  }

  // Asked again inside the wait: nothing new is sent and the earlier secret stays valid, so the
  // person still has the email they already got. The caller sees the same as for any request.
  @Test
  void sendsNothingNewWhenAskedAgainWithinTheWait() {
    Email email = new Email("impatient@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    // Issued ten seconds ago: both kinds live ten minutes.
    when(tokens.findLatestActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.EMAIL_SIGN_IN_CODE), any(Instant.class)))
        .thenReturn(
            Optional.of(
                VerificationToken.issue(
                    account.id(),
                    VerificationTokenType.EMAIL_SIGN_IN_CODE,
                    "earlier-hash",
                    Instant.now().plusSeconds(600).minusSeconds(10))));

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(tokens, never()).save(any());
    verify(tokens, never()).invalidateActiveOfTypeForAccount(any(), any(), any());
    verify(mailSender, never()).sendEmailSignInCode(any(), any(), any());
  }

  @Test
  void sendsANewOneOnceTheWaitHasPassed() {
    Email email = new Email("patient@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(tokens.findLatestActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.EMAIL_SIGN_IN_CODE), any(Instant.class)))
        .thenReturn(
            Optional.of(
                VerificationToken.issue(
                    account.id(),
                    VerificationTokenType.EMAIL_SIGN_IN_CODE,
                    "earlier-hash",
                    Instant.now().plusSeconds(600).minusSeconds(45))));

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(tokens).save(any());
    verify(mailSender).sendEmailSignInCode(eq(email.value()), eq(organizationId), any());
  }

  // ADR-0033: while the method is paused for an address a code could not be redeemed, so none is
  // issued or mailed. Silent, like the wait.
  @Test
  void sendsNothingWhileTheMethodIsPausedForTheAddress() {
    Email email = new Email("paused@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String key = CodeLockout.signInCodeKey(organizationId, email);
    for (int attempt = 0; attempt < 3; attempt++) {
      tracker.recordFailure(key);
    }

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendEmailSignInCode(any(), any(), any());
  }
}
