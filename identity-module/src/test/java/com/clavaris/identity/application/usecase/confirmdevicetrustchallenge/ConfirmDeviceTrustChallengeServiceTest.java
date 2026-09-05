package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmDeviceTrustChallengeServiceTest {

  private final AccountId accountId = AccountId.newId();

  private VerificationTokenRepository tokens;
  private ConfirmDeviceTrustChallengeService service;

  @BeforeEach
  void setUp() {
    tokens = mock(VerificationTokenRepository.class);
    service = new ConfirmDeviceTrustChallengeService(tokens);
  }

  private VerificationToken issuedToken(final String rawCode) {
    return VerificationToken.issue(
        accountId,
        VerificationTokenType.DEVICE_TRUST_CHALLENGE,
        RefreshTokenSecret.hash(rawCode),
        Instant.now().plusSeconds(600));
  }

  @Test
  void consumesAValidCodeForTheExactPendingAccount() {
    String rawCode = "482913";
    VerificationToken token = issuedToken(rawCode);
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawCode))).thenReturn(Optional.of(token));

    service.handle(new ConfirmDeviceTrustChallengeCommand(accountId, rawCode));

    assertThat(token.consumedAt()).isPresent();
    verify(tokens).save(token);
  }

  @Test
  void rejectsAnUnknownCode() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
    ConfirmDeviceTrustChallengeCommand command =
        new ConfirmDeviceTrustChallengeCommand(accountId, "000000");

    assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(tokens, never()).save(any());
  }

  @Test
  void rejectsATokenBelongingToADifferentAccount() {
    String rawCode = "111222";
    VerificationToken token = issuedToken(rawCode);
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawCode))).thenReturn(Optional.of(token));
    AccountId anotherAccountId = AccountId.newId();
    ConfirmDeviceTrustChallengeCommand command =
        new ConfirmDeviceTrustChallengeCommand(anotherAccountId, rawCode);

    assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(tokens, never()).save(any());
  }

  @Test
  void rejectsATokenOfTheWrongType() {
    String rawCode = "333444";
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.EMAIL_SIGN_IN_CODE,
            RefreshTokenSecret.hash(rawCode),
            Instant.now().plusSeconds(600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawCode))).thenReturn(Optional.of(token));
    ConfirmDeviceTrustChallengeCommand command =
        new ConfirmDeviceTrustChallengeCommand(accountId, rawCode);

    assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(tokens, never()).save(any());
  }

  @Test
  void rejectsAnExpiredCode() {
    String rawCode = "555666";
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.DEVICE_TRUST_CHALLENGE,
            RefreshTokenSecret.hash(rawCode),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawCode))).thenReturn(Optional.of(token));
    ConfirmDeviceTrustChallengeCommand command =
        new ConfirmDeviceTrustChallengeCommand(accountId, rawCode);

    assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
