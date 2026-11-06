package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.codelockout.InMemoryCodeAttemptTracker;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmDeviceTrustChallengeServiceTest {

  private final AccountId accountId = AccountId.newId();

  private VerificationTokenRepository tokens;
  private InMemoryCodeAttemptTracker tracker;
  private AccountRepository accounts;
  private ConfirmDeviceTrustChallengeService service;

  @BeforeEach
  void setUp() {
    tokens = mock(VerificationTokenRepository.class);
    tracker = new InMemoryCodeAttemptTracker(3);
    accounts = mock(AccountRepository.class);
    service =
        new ConfirmDeviceTrustChallengeService(
            tokens,
            new CodeLockout(
                tracker,
                accounts,
                mock(MailSender.class),
                mock(AuditEventRecorder.class),
                Runnable::run));
  }

  private VerificationToken issuedToken(final String rawCode) {
    return VerificationToken.issue(
        accountId,
        VerificationTokenType.DEVICE_TRUST_CHALLENGE,
        EmailOneTimeCode.hashFor(accountId, rawCode),
        Instant.now().plusSeconds(600));
  }

  @Test
  void consumesAValidCodeForTheExactPendingAccount() {
    String rawCode = "482913";
    VerificationToken token = issuedToken(rawCode);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);

    service.handle(new ConfirmDeviceTrustChallengeCommand(accountId, rawCode));

    verify(tokens).consumeIfActive(eq(token.id()), any(Instant.class));
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
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, rawCode)))
        .thenReturn(Optional.of(token));
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
            EmailOneTimeCode.hashFor(accountId, rawCode),
            Instant.now().plusSeconds(600));
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, rawCode)))
        .thenReturn(Optional.of(token));
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
            EmailOneTimeCode.hashFor(accountId, rawCode),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, rawCode)))
        .thenReturn(Optional.of(token));
    ConfirmDeviceTrustChallengeCommand command =
        new ConfirmDeviceTrustChallengeCommand(accountId, rawCode);

    assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
        .isThrownBy(() -> service.handle(command));
  }

  // The race this closes: consume() then save() let two submissions of the same code both pass.
  @Test
  void rejectsACodeAnotherRequestConsumedFirst() {
    String rawCode = "482913";
    VerificationToken token = issuedToken(rawCode);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(false);
    ConfirmDeviceTrustChallengeCommand command =
        new ConfirmDeviceTrustChallengeCommand(accountId, rawCode);

    assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
        .isThrownBy(() -> service.handle(command));
  }

  // ADR-0033: this code had no limit on attempts at all.
  @Test
  void tooManyWrongCodesInARowPauseTheVerificationEvenForTheRightCode() {
    String rawCode = "482913";
    VerificationToken token = issuedToken(rawCode);
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);
    ConfirmDeviceTrustChallengeCommand wrong =
        new ConfirmDeviceTrustChallengeCommand(accountId, "000000");
    ConfirmDeviceTrustChallengeCommand right =
        new ConfirmDeviceTrustChallengeCommand(accountId, rawCode);

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
          .isThrownBy(() -> service.handle(wrong));
    }

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> service.handle(right));
  }

  // TD-SEC-093: the attempt is taken before the code is judged. With the limit's worth of entries
  // already in flight (taken, not yet found wrong), the next is refused unjudged even if right.
  @Test
  void anEntryIsRefusedUnjudgedWhenTheLimitsWorthAreAlreadyInFlight() {
    String key = CodeLockout.deviceCodeKey(accountId);
    for (int inFlight = 0; inFlight < 3; inFlight++) {
      tracker.tryReserveAttempt(key);
    }

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(
            () -> service.handle(new ConfirmDeviceTrustChallengeCommand(accountId, "482913")));
    verify(tokens, never()).findByTokenHash(any());
  }

  @Test
  void aRightCodeClearsTheCount() {
    String rawCode = "482913";
    VerificationToken token = issuedToken(rawCode);
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);
    ConfirmDeviceTrustChallengeCommand wrong =
        new ConfirmDeviceTrustChallengeCommand(accountId, "000000");
    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
          .isThrownBy(() -> service.handle(wrong));
    }

    service.handle(new ConfirmDeviceTrustChallengeCommand(accountId, rawCode));

    assertThat(tracker.failuresOf(CodeLockout.deviceCodeKey(accountId))).isZero();
  }

  @Test
  void acceptsTheCodeWithSpacesOrHyphensButOnlyInCapitals() {
    String issued = "K7M2QX";
    VerificationToken token = issuedToken(issued);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, issued)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);

    service.handle(new ConfirmDeviceTrustChallengeCommand(accountId, " K7M-2QX "));

    verify(tokens).consumeIfActive(eq(token.id()), any(Instant.class));
  }

  // The page sends capitals; a lower-case value is not the code, and counts as a wrong one.
  @Test
  void aLowerCaseCodeIsRejected() {
    String issued = "K7M2QX";
    VerificationToken token = issuedToken(issued);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(accountId, issued)))
        .thenReturn(Optional.of(token));
    ConfirmDeviceTrustChallengeCommand lower =
        new ConfirmDeviceTrustChallengeCommand(accountId, "k7m2qx");

    assertThatExceptionOfType(InvalidDeviceTrustChallengeException.class)
        .isThrownBy(() -> service.handle(lower));

    verify(tokens, never()).consumeIfActive(any(), any());
  }

  // The page asks before showing the form: no code is sent while the verification is paused.
  @Test
  void reportsWhetherTheVerificationIsPaused() {
    assertThat(service.isPaused(accountId)).isFalse();
    String key = CodeLockout.deviceCodeKey(accountId);
    for (int attempt = 0; attempt < 3; attempt++) {
      tracker.tryReserveAttempt(key);
      tracker.settleFailedAttempt(key);
    }

    assertThat(service.isPaused(accountId)).isTrue();
  }
}
