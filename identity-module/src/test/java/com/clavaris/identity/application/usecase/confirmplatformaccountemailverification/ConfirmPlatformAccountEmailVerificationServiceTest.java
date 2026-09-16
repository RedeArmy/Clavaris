package com.clavaris.identity.application.usecase.confirmplatformaccountemailverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    // Default: this call "wins" the conditional-consume race (SDE-III review, 2026-09-15) — every
    // test below exercises the ordinary, uncontested confirm path unless it deliberately overrides
    // this stub to prove the lost-race path itself.
    when(tokens.consumeIfActive(any(), any())).thenReturn(true);
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

    assertThat(account.emailVerifiedAt()).isPresent();
    verify(tokens).consumeIfActive(eq(token.id()), any()); // the atomic consume
    verify(tokens, never()).save(any()); // no plain save — the conditional update did it
    verify(accounts).save(account);
  }

  @Test
  void rejectsAnUnknownToken() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
    ConfirmPlatformAccountEmailVerificationCommand command =
        new ConfirmPlatformAccountEmailVerificationCommand("garbage");

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(() -> service.handle(command));

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
    ConfirmPlatformAccountEmailVerificationCommand command =
        new ConfirmPlatformAccountEmailVerificationCommand(rawToken);

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void losingTheConditionalConsumeRaceIsTreatedAsAnInvalidToken() {
    // TOCTOU regression test (SDE-III review, 2026-09-15) — see
    // ConfirmPasswordResetServiceTest's own identical test for the full rationale.
    PlatformAccount account = PlatformAccount.register(new Email("raced-founder@example.com"));
    String rawToken = "raced-platform-verification-token";
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any())).thenReturn(false);
    ConfirmPlatformAccountEmailVerificationCommand command =
        new ConfirmPlatformAccountEmailVerificationCommand(rawToken);

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }
}
