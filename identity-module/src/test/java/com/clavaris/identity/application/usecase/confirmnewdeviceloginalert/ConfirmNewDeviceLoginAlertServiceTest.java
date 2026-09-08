package com.clavaris.identity.application.usecase.confirmnewdeviceloginalert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountCommand;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmNewDeviceLoginAlertServiceTest {

  private VerificationTokenRepository tokens;
  private SuspendAccountUseCase suspendAccount;
  private ConfirmNewDeviceLoginAlertService service;

  @BeforeEach
  void setUp() {
    tokens = mock(VerificationTokenRepository.class);
    suspendAccount = mock(SuspendAccountUseCase.class);
    service = new ConfirmNewDeviceLoginAlertService(tokens, suspendAccount);
  }

  @Test
  void aValidTokenConsumesItAndSuspendsTheAccountAsTheAccountHolderThemselves() {
    AccountId accountId = new AccountId(UUID.randomUUID());
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(token));

    AccountId result = service.handle(new ConfirmNewDeviceLoginAlertCommand("the-raw-token"));

    assertThat(result).isEqualTo(accountId);
    assertThat(token.isActive()).isFalse();
    verify(tokens).save(token);
    // BR-ID-08's own reversible-ban cascade, but as the account holder themselves — never
    // AuditActor#platformClient, the tier every other SuspendAccountCommand caller uses.
    verify(suspendAccount)
        .handle(new SuspendAccountCommand(accountId, AuditActor.account(accountId.value())));
  }

  @Test
  void anUnknownTokenIsRejectedWithoutSuspendingAnything() {
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("unknown-token")))
        .thenReturn(Optional.empty());
    ConfirmNewDeviceLoginAlertCommand command =
        new ConfirmNewDeviceLoginAlertCommand("unknown-token");

    assertThatExceptionOfType(InvalidNewDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendAccount, never()).handle(any());
  }

  // Defense in depth: a token hash collision with a different VerificationTokenType must never
  // be honored here, same "check type itself after the hash lookup" rationale
  // VerificationTokenRepository's own Javadoc documents.
  @Test
  void aTokenOfADifferentTypeIsRejectedEvenIfTheHashMatches() {
    AccountId accountId = new AccountId(UUID.randomUUID());
    VerificationToken wrongTypeToken =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(wrongTypeToken));
    ConfirmNewDeviceLoginAlertCommand command =
        new ConfirmNewDeviceLoginAlertCommand("the-raw-token");

    assertThatExceptionOfType(InvalidNewDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendAccount, never()).handle(any());
  }

  @Test
  void anAlreadyConsumedTokenIsRejected() {
    AccountId accountId = new AccountId(UUID.randomUUID());
    VerificationToken consumedToken =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().plusSeconds(3600));
    consumedToken.consume();
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(consumedToken));
    ConfirmNewDeviceLoginAlertCommand command =
        new ConfirmNewDeviceLoginAlertCommand("the-raw-token");

    assertThatExceptionOfType(InvalidNewDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendAccount, never()).handle(any());
  }

  @Test
  void anExpiredTokenIsRejected() {
    AccountId accountId = new AccountId(UUID.randomUUID());
    VerificationToken expiredToken =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
            RefreshTokenSecret.hash("the-raw-token"),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash("the-raw-token")))
        .thenReturn(Optional.of(expiredToken));
    ConfirmNewDeviceLoginAlertCommand command =
        new ConfirmNewDeviceLoginAlertCommand("the-raw-token");

    assertThatExceptionOfType(InvalidNewDeviceLoginAlertException.class)
        .isThrownBy(() -> service.handle(command));

    verify(suspendAccount, never()).handle(any());
  }
}
