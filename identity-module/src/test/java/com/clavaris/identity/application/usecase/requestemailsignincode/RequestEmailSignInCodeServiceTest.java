package com.clavaris.identity.application.usecase.requestemailsignincode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.OrganizationEnvironmentChecker;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestEmailSignInCodeServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private MailSender mailSender;
  private OrganizationEnvironmentChecker environmentChecker;
  private AccountAuthenticationPolicyProvider policyProvider;
  private RequestEmailSignInCodeService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    mailSender = mock(MailSender.class);
    environmentChecker = mock(OrganizationEnvironmentChecker.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    service =
        new RequestEmailSignInCodeService(
            accounts, tokens, mailSender, environmentChecker, policyProvider);
    when(policyProvider.policyFor(organizationId)).thenReturn(enabledPolicy());
  }

  private static AccountAuthenticationPolicySnapshot enabledPolicy() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, true, false, false, false, false, true, false);
  }

  @Test
  void issuesACodeTokenAndEmailsItWhenTheAccountExists() {
    Email email = new Email("code-flow@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(environmentChecker.isDevelopment(organizationId)).thenReturn(false);

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

  @Test
  void bypassesEmailDeliveryInADevelopmentEnvironment() {
    Email email = new Email("dev-org@example.com");
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(environmentChecker.isDevelopment(organizationId)).thenReturn(true);

    service.handle(new RequestEmailSignInCodeCommand(organizationId, email));

    verify(tokens).save(any());
    verify(mailSender, never()).sendEmailSignInCode(any(), any(), any());
  }

  @Test
  void rejectsWhenTheOrganizationHasNotEnabledEmailCodeSignIn() {
    when(policyProvider.policyFor(organizationId))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    Email email = new Email("someone@example.com");

    assertThatExceptionOfType(EmailCodeSignInNotEnabledException.class)
        .isThrownBy(() -> service.handle(new RequestEmailSignInCodeCommand(organizationId, email)));

    verify(accounts, never()).findByOrganizationIdAndEmail(any(), any());
  }
}
