package com.clavaris.identity.application.usecase.requestdevicetrustchallenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
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

class RequestDeviceTrustChallengeServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private MailSender mailSender;
  private RequestDeviceTrustChallengeService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    mailSender = mock(MailSender.class);
    service = new RequestDeviceTrustChallengeService(accounts, tokens, mailSender);
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
}
