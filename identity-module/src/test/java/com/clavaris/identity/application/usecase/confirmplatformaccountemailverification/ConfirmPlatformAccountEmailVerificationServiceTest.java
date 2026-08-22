package com.clavaris.identity.application.usecase.confirmplatformaccountemailverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmPlatformAccountEmailVerificationServiceTest {

  private PlatformVerificationTokenRepository tokens;
  private PlatformAccountRepository accounts;
  private ConfirmPlatformAccountEmailVerificationService service;

  @BeforeEach
  void setUp() {
    tokens = mock(PlatformVerificationTokenRepository.class);
    accounts = mock(PlatformAccountRepository.class);
    service = new ConfirmPlatformAccountEmailVerificationService(tokens, accounts);
  }

  @Test
  void consumesAnActiveTokenAndMarksTheAccountVerified() {
    PlatformAccount account = PlatformAccount.register(new Email("founder@example.com"));
    String rawToken = "a-valid-token-value";
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new ConfirmPlatformAccountEmailVerificationCommand(rawToken));

    assertThat(token.consumedAt()).isPresent();
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(tokens).save(token);
    verify(accounts).save(account);
  }

  @Test
  void rejectsAnUnknownToken() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(
            () -> service.handle(new ConfirmPlatformAccountEmailVerificationCommand("garbage")));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsATokenOfTheWrongType() {
    String rawToken = "a-password-reset-token";
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            PlatformAccountId.newId(),
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(
            () -> service.handle(new ConfirmPlatformAccountEmailVerificationCommand(rawToken)));

    verify(accounts, never()).save(any());
  }
}
