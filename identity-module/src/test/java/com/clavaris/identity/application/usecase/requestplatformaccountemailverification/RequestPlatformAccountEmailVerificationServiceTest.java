package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestPlatformAccountEmailVerificationServiceTest {

  private PlatformAccountRepository accounts;
  private PlatformVerificationTokenRepository tokens;
  private PlatformMailSender mailSender;
  private RequestPlatformAccountEmailVerificationService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    tokens = mock(PlatformVerificationTokenRepository.class);
    mailSender = mock(PlatformMailSender.class);
    service = new RequestPlatformAccountEmailVerificationService(accounts, tokens, mailSender);
  }

  @Test
  void issuesATokenAndSendsAVerificationEmailForAnUnverifiedAccount() {
    PlatformAccount account = PlatformAccount.register(new Email("founder@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestPlatformAccountEmailVerificationCommand(account.id()));

    ArgumentCaptor<PlatformVerificationToken> captor =
        ArgumentCaptor.forClass(PlatformVerificationToken.class);
    verify(tokens).save(captor.capture());
    assertThat(captor.getValue().platformAccountId()).isEqualTo(account.id());
    assertThat(captor.getValue().type()).isEqualTo(VerificationTokenType.EMAIL_VERIFICATION);
    verify(mailSender).sendPlatformAccountEmailVerification(eq("founder@example.com"), any());
  }

  @Test
  void isANoOpForAnAlreadyVerifiedAccount() {
    PlatformAccount account = PlatformAccount.register(new Email("founder@example.com"));
    account.verifyEmail();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new RequestPlatformAccountEmailVerificationCommand(account.id()));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendPlatformAccountEmailVerification(any(), any());
  }

  @Test
  void rejectsAnUnknownPlatformAccountId() {
    PlatformAccountId unknownId = PlatformAccountId.newId();
    when(accounts.findById(unknownId)).thenReturn(Optional.empty());
    RequestPlatformAccountEmailVerificationCommand command =
        new RequestPlatformAccountEmailVerificationCommand(unknownId);

    assertThatExceptionOfType(UnknownPlatformAccountException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
