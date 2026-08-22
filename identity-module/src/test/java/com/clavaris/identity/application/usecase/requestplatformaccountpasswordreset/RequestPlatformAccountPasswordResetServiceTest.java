package com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestPlatformAccountPasswordResetServiceTest {

  private final Email email = new Email("founder@example.com");

  private PlatformAccountRepository accounts;
  private PlatformVerificationTokenRepository tokens;
  private PlatformMailSender mailSender;
  private RequestPlatformAccountPasswordResetService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    tokens = mock(PlatformVerificationTokenRepository.class);
    mailSender = mock(PlatformMailSender.class);
    service = new RequestPlatformAccountPasswordResetService(accounts, tokens, mailSender);
  }

  @Test
  void issuesAPasswordResetTokenAndSendsTheEmailForAKnownAccount() {
    PlatformAccount account = PlatformAccount.register(email);
    account.attachPasswordCredential("existing-hash");
    when(accounts.findByEmail(email)).thenReturn(Optional.of(account));

    service.handle(new RequestPlatformAccountPasswordResetCommand(email));

    ArgumentCaptor<PlatformVerificationToken> captor =
        ArgumentCaptor.forClass(PlatformVerificationToken.class);
    verify(tokens).save(captor.capture());
    assertThat(captor.getValue().type()).isEqualTo(VerificationTokenType.PASSWORD_RESET);
    verify(mailSender).sendPlatformAccountPasswordReset(eq(email.value()), any());
  }

  @Test
  void isANoOpThatDoesNotRevealWhetherTheAccountExists() {
    when(accounts.findByEmail(email)).thenReturn(Optional.empty());

    service.handle(new RequestPlatformAccountPasswordResetCommand(email));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendPlatformAccountPasswordReset(any(), any());
  }
}
