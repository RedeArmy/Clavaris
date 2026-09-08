package com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountCommand;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountUseCase;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmNewPlatformDeviceLoginAlertServiceTest {

  private PlatformVerificationTokenRepository tokens;
  private SuspendPlatformAccountUseCase suspendPlatformAccount;
  private ConfirmNewPlatformDeviceLoginAlertService service;

  @BeforeEach
  void setUp() {
    tokens = mock(PlatformVerificationTokenRepository.class);
    suspendPlatformAccount = mock(SuspendPlatformAccountUseCase.class);
    service = new ConfirmNewPlatformDeviceLoginAlertService(tokens, suspendPlatformAccount);
  }

  @Test
  void aValidTokenConsumesItAndSuspendsTheAccountAsTheAccountHolderThemselves() {
    PlatformAccountId platformAccountId = new PlatformAccountId(UUID.randomUUID());
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            platformAccountId,
            VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(token));

    PlatformAccountId result =
        service.handle(new ConfirmNewPlatformDeviceLoginAlertCommand("the-raw-token"));

    assertThat(result).isEqualTo(platformAccountId);
    assertThat(token.isActive()).isFalse();
    verify(tokens).save(token);
    verify(suspendPlatformAccount)
        .handle(
            new SuspendPlatformAccountCommand(
                platformAccountId, AuditActor.platformAccount(platformAccountId.value())));
  }

  @Test
  void anUnknownTokenIsRejectedWithoutSuspendingAnything() {
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("unknown-token")))
        .thenReturn(Optional.empty());
    ConfirmNewPlatformDeviceLoginAlertCommand command =
        new ConfirmNewPlatformDeviceLoginAlertCommand("unknown-token");

    assertThatExceptionOfType(InvalidNewPlatformDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendPlatformAccount, never()).handle(any());
  }

  @Test
  void aTokenOfADifferentTypeIsRejectedEvenIfTheHashMatches() {
    PlatformAccountId platformAccountId = new PlatformAccountId(UUID.randomUUID());
    PlatformVerificationToken wrongTypeToken =
        PlatformVerificationToken.issue(
            platformAccountId,
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(wrongTypeToken));
    ConfirmNewPlatformDeviceLoginAlertCommand command =
        new ConfirmNewPlatformDeviceLoginAlertCommand("the-raw-token");

    assertThatExceptionOfType(InvalidNewPlatformDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendPlatformAccount, never()).handle(any());
  }

  @Test
  void anAlreadyConsumedTokenIsRejected() {
    PlatformAccountId platformAccountId = new PlatformAccountId(UUID.randomUUID());
    PlatformVerificationToken consumedToken =
        PlatformVerificationToken.issue(
            platformAccountId,
            VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().plusSeconds(3600));
    consumedToken.consume();
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(consumedToken));
    ConfirmNewPlatformDeviceLoginAlertCommand command =
        new ConfirmNewPlatformDeviceLoginAlertCommand("the-raw-token");

    assertThatExceptionOfType(InvalidNewPlatformDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendPlatformAccount, never()).handle(any());
  }

  @Test
  void anExpiredTokenIsRejected() {
    PlatformAccountId platformAccountId = new PlatformAccountId(UUID.randomUUID());
    PlatformVerificationToken expiredToken =
        PlatformVerificationToken.issue(
            platformAccountId,
            VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(expiredToken));
    ConfirmNewPlatformDeviceLoginAlertCommand command =
        new ConfirmNewPlatformDeviceLoginAlertCommand("the-raw-token");

    assertThatExceptionOfType(InvalidNewPlatformDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendPlatformAccount, never()).handle(any());
  }
}
