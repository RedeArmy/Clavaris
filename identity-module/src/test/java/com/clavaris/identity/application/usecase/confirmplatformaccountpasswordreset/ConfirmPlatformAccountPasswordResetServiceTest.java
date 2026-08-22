package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
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

class ConfirmPlatformAccountPasswordResetServiceTest {

  private PlatformVerificationTokenRepository tokens;
  private PlatformAccountRepository accounts;
  private PlatformAccountSessionRevoker sessionRevoker;
  private PasswordHasher hasher;
  private ConfirmPlatformAccountPasswordResetService service;

  @BeforeEach
  void setUp() {
    tokens = mock(PlatformVerificationTokenRepository.class);
    accounts = mock(PlatformAccountRepository.class);
    sessionRevoker = mock(PlatformAccountSessionRevoker.class);
    hasher = mock(PasswordHasher.class);
    service =
        new ConfirmPlatformAccountPasswordResetService(tokens, accounts, sessionRevoker, hasher);
    when(hasher.hash(anyString())).thenReturn("argon2id$new-hash");
  }

  private PlatformAccount accountWithPassword() {
    PlatformAccount account = PlatformAccount.register(new Email("founder@example.com"));
    account.attachPasswordCredential("argon2id$old-hash");
    return account;
  }

  @Test
  void resetsThePasswordAndRevokesEverySession() {
    PlatformAccount account = accountWithPassword();
    String rawToken = "a-valid-reset-token";
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(1800));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new ConfirmPlatformAccountPasswordResetCommand(rawToken, "a-Str0ng-Password!"));

    assertThat(token.consumedAt()).isPresent();
    assertThat(account.passwordCredential().orElseThrow().passwordHash())
        .isEqualTo("argon2id$new-hash");
    verify(accounts).save(account);
    verify(sessionRevoker).revokeAllSessionsFor(account.id());
  }

  @Test
  void rejectsAWeakNewPasswordBeforeConsumingTheToken() {
    assertThatExceptionOfType(WeakPasswordException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new ConfirmPlatformAccountPasswordResetCommand("any-token", "weak")));

    verify(tokens, never()).findByTokenHash(any());
    verify(sessionRevoker, never()).revokeAllSessionsFor(any());
  }

  @Test
  void rejectsAnUnknownToken() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new ConfirmPlatformAccountPasswordResetCommand(
                        "garbage", "a-Str0ng-Password!")));

    verify(accounts, never()).save(any());
    verify(sessionRevoker, never()).revokeAllSessionsFor(any());
  }

  @Test
  void rejectsATokenOfTheWrongType() {
    String rawToken = "an-email-verification-token";
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            PlatformAccountId.newId(),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(1800));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new ConfirmPlatformAccountPasswordResetCommand(
                        rawToken, "a-Str0ng-Password!")));

    verify(accounts, never()).save(any());
  }
}
