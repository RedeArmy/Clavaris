package com.clavaris.identity.application.usecase.requestemailverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestEmailVerificationServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private MailSender mailSender;
  private OrganizationEnvironmentChecker environmentChecker;
  private AccountAuthenticationPolicyProvider policyProvider;
  private RequestEmailVerificationService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    mailSender = mock(MailSender.class);
    environmentChecker = mock(OrganizationEnvironmentChecker.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    // Matches today's real default (ADR-0024: LINK) — every existing test below relies on this
    // exact send path being taken by default, same as before this port existed. Mockito's own
    // default (unstubbed boolean = false) already means "not DEVELOPMENT".
    when(policyProvider.policyFor(organizationId))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    service =
        new RequestEmailVerificationService(
            accounts, tokens, mailSender, environmentChecker, policyProvider);
  }

  @Test
  void issuesATokenAndSendsAVerificationEmailForAnUnverifiedAccount() {
    Account account = Account.register(organizationId, new Email("new-user@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestEmailVerificationCommand(account.id()));

    verify(tokens, times(1)).save(any());
    verify(mailSender).sendEmailVerification(eq("new-user@example.com"), eq(organizationId), any());
  }

  @Test
  void theSavedTokenIsScopedToTheAccountAndTypedAsEmailVerification() {
    Account account = Account.register(organizationId, new Email("new-user@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestEmailVerificationCommand(account.id()));

    ArgumentCaptor<VerificationToken> captor = ArgumentCaptor.forClass(VerificationToken.class);
    verify(tokens).save(captor.capture());
    assertThat(captor.getValue().accountId()).isEqualTo(account.id());
    assertThat(captor.getValue().type()).isEqualTo(VerificationTokenType.EMAIL_VERIFICATION);
    assertThat(captor.getValue().isActive()).isTrue();
  }

  @Test
  void isANoOpForAnAlreadyVerifiedAccount() {
    Account account = Account.register(organizationId, new Email("already-verified@example.com"));
    account.verifyEmail();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestEmailVerificationCommand(account.id()));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendEmailVerification(any(), any(), any());
  }

  @Test
  void rejectsAnUnknownAccountId() {
    AccountId unknownId = new AccountId(UUID.randomUUID());
    when(accounts.findById(unknownId)).thenReturn(Optional.empty());
    RequestEmailVerificationCommand command = new RequestEmailVerificationCommand(unknownId);

    assertThatExceptionOfType(UnknownAccountException.class)
        .isThrownBy(() -> service.handle(command));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendEmailVerification(any(), any(), any());
  }

  // ADR-0024 §2
  @Test
  void issuesAShortCodeInsteadOfALinkWhenThePolicySaysCode() {
    Account account = Account.register(organizationId, new Email("code-user@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(policyProvider.policyFor(organizationId))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false,
                EmailVerificationMethod.CODE,
                false,
                false,
                false,
                false,
                false,
                true,
                false));

    service.handle(new RequestEmailVerificationCommand(account.id()));

    verify(tokens, times(1)).save(any());
    verify(mailSender, never()).sendEmailVerification(any(), any(), any());
    verify(mailSender)
        .sendEmailVerificationCode(eq("code-user@example.com"), eq(organizationId), any());
  }

  @Test
  void issuesBothALinkAndACodeWhenThePolicySaysBoth() {
    Account account = Account.register(organizationId, new Email("both-user@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(policyProvider.policyFor(organizationId))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false,
                EmailVerificationMethod.BOTH,
                false,
                false,
                false,
                false,
                false,
                true,
                false));

    service.handle(new RequestEmailVerificationCommand(account.id()));

    verify(tokens, times(2)).save(any());
    verify(mailSender)
        .sendEmailVerification(eq("both-user@example.com"), eq(organizationId), any());
    verify(mailSender)
        .sendEmailVerificationCode(eq("both-user@example.com"), eq(organizationId), any());
  }

  // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis).
  @Test
  void stillIssuesATokenButNeverSendsARealEmailForADevelopmentEnvironmentAccount() {
    Account account = Account.register(organizationId, new Email("sandbox-user@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(environmentChecker.isDevelopment(organizationId)).thenReturn(true);

    service.handle(new RequestEmailVerificationCommand(account.id()));

    verify(tokens, times(1)).save(any());
    verify(mailSender, never()).sendEmailVerification(any(), any(), any());
  }
}
