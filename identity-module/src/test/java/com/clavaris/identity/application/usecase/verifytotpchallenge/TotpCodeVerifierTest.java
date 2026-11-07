package com.clavaris.identity.application.usecase.verifytotpchallenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.CountingTracker;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.FakeCipher;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.InMemoryCredentials;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.InMemoryRecoveryCodes;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.TotpCredential;
import com.clavaris.identity.domain.service.Base32;
import com.clavaris.identity.domain.service.RecoveryCodeFormat;
import com.clavaris.identity.domain.service.TotpAlgorithm;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TotpCodeVerifierTest {

  private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

  private final AccountId accountId = AccountId.newId();
  private final byte[] secret = TotpAlgorithm.newSecret();
  private final InMemoryCredentials credentials = new InMemoryCredentials();
  private final InMemoryRecoveryCodes recoveryCodes = new InMemoryRecoveryCodes();
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private TotpCodeVerifier verifier;
  private long currentStep;

  @BeforeEach
  void setUp() {
    final TotpCredential credential =
        TotpCredential.pending(
            accountId,
            new OrganizationId(UUID.randomUUID()),
            new FakeCipher().encrypt(accountId, Base32.encode(secret)));
    credential.confirm(NOW.minusSeconds(3600), 0);
    credentials.save(credential);
    currentStep = TotpAlgorithm.stepAt(NOW.getEpochSecond());
    verifier =
        new TotpCodeVerifier(
            credentials,
            recoveryCodes,
            new FakeCipher(),
            TotpTestSupport.lockout(new CountingTracker(3)),
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String codeFor(final long step) {
    return TotpAlgorithm.codeAt(secret, step);
  }

  @Test
  void aCurrentCodeIsAccepted() {
    assertThat(verifier.verify(accountId, codeFor(currentStep)))
        .isEqualTo(TotpMethod.AUTHENTICATOR);
  }

  @Test
  void aCodeOneStepOffIsAcceptedForClockDrift() {
    assertThat(verifier.verify(accountId, codeFor(currentStep + 1)))
        .isEqualTo(TotpMethod.AUTHENTICATOR);
  }

  @Test
  void aCodeTwoStepsOffIsRefused() {
    final String tooFar = codeFor(currentStep + 2);

    assertThatThrownBy(() -> verifier.verify(accountId, tooFar))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void aCodeWorksOnlyOnce() {
    final String code = codeFor(currentStep);
    verifier.verify(accountId, code);

    assertThatThrownBy(() -> verifier.verify(accountId, code))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void anEarlierCodeIsRefusedOnceALaterOneWasSpent() {
    verifier.verify(accountId, codeFor(currentStep + 1));

    final String earlier = codeFor(currentStep);

    assertThatThrownBy(() -> verifier.verify(accountId, earlier))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void aWrongCodeIsRefused() {
    assertThatThrownBy(() -> verifier.verify(accountId, "000000"))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void aRecoveryCodeWorksOnceAndIsAudited() {
    final RecoveryCodeFormat.Set set = RecoveryCodeFormat.newSet();
    recoveryCodes.replaceAll(accountId, new OrganizationId(UUID.randomUUID()), set.hashes());
    final String typed = set.codes().get(0).toLowerCase(java.util.Locale.ROOT);

    assertThat(verifier.verify(accountId, typed)).isEqualTo(TotpMethod.RECOVERY_CODE);
    verify(audit)
        .write(any(), eq("account.totp_recovery_code_used"), any(), any(), eq("remaining=9"));
    assertThatThrownBy(() -> verifier.verify(accountId, typed))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void aRecoveryCodeOfAnotherAccountIsRefused() {
    final RecoveryCodeFormat.Set set = RecoveryCodeFormat.newSet();
    recoveryCodes.replaceAll(
        AccountId.newId(), new OrganizationId(UUID.randomUUID()), set.hashes());

    final String someoneElses = set.codes().get(0);

    assertThatThrownBy(() -> verifier.verify(accountId, someoneElses))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void anUnconfirmedSetupIsNotACredentialToCheckAgainst() {
    final AccountId other = AccountId.newId();
    credentials.save(
        TotpCredential.pending(
            other,
            new OrganizationId(UUID.randomUUID()),
            new FakeCipher().encrypt(other, Base32.encode(secret))));

    final String code = codeFor(currentStep);

    assertThatThrownBy(() -> verifier.verify(other, code))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void garbageAndNullAreRefusedWithoutError() {
    assertThatThrownBy(() -> verifier.verify(accountId, null))
        .isInstanceOf(InvalidTotpCodeException.class);
    assertThatThrownBy(() -> verifier.verify(accountId, "not a code at all"))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void theMethodIsPausedAfterTooManyWrongEntriesAndEvenARightCodeIsRefusedWhilePaused() {
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> verifier.verify(accountId, "000000"))
          .isInstanceOf(InvalidTotpCodeException.class);
    }

    final String rightCode = codeFor(currentStep);

    assertThatThrownBy(() -> verifier.verify(accountId, rightCode))
        .isInstanceOf(CodeTemporarilyLockedException.class);
  }

  // TD-SEC-092: whoever knows the password can pause the app by guessing on purpose; the owner's
  // recovery codes, which are what gets them in meanwhile, are counted and paused apart.
  @Test
  void aPausedAuthenticatorCodeDoesNotPauseTheRecoveryCodes() {
    final RecoveryCodeFormat.Set set = RecoveryCodeFormat.newSet();
    recoveryCodes.replaceAll(accountId, new OrganizationId(UUID.randomUUID()), set.hashes());
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> verifier.verify(accountId, "000000"))
          .isInstanceOf(InvalidTotpCodeException.class);
    }
    final String rightCode = codeFor(currentStep);
    assertThatThrownBy(() -> verifier.verify(accountId, rightCode))
        .isInstanceOf(CodeTemporarilyLockedException.class);

    assertThat(verifier.verify(accountId, set.codes().get(0))).isEqualTo(TotpMethod.RECOVERY_CODE);
  }

  @Test
  void aRecoveryCodeAlsoEndsThePauseOnTheApp() {
    final RecoveryCodeFormat.Set set = RecoveryCodeFormat.newSet();
    recoveryCodes.replaceAll(accountId, new OrganizationId(UUID.randomUUID()), set.hashes());
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> verifier.verify(accountId, "000000"))
          .isInstanceOf(InvalidTotpCodeException.class);
    }
    verifier.verify(accountId, set.codes().get(0));

    assertThat(verifier.verify(accountId, codeFor(currentStep)))
        .isEqualTo(TotpMethod.AUTHENTICATOR);
  }

  @Test
  void wrongRecoveryCodesAreCountedAndPausedOnTheirOwn() {
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> verifier.verify(accountId, "ABCD-EFGH-IJKL-MNOP"))
          .isInstanceOf(InvalidTotpCodeException.class);
    }

    assertThatThrownBy(() -> verifier.verify(accountId, "ABCD-EFGH-IJKL-MNOP"))
        .isInstanceOf(CodeTemporarilyLockedException.class);
    assertThat(verifier.verify(accountId, codeFor(currentStep)))
        .as("the app is still open")
        .isEqualTo(TotpMethod.AUTHENTICATOR);
  }

  @Test
  void aRightCodeClearsTheCountOfWrongOnes() {
    final CountingTracker tracker = new CountingTracker(3);
    final TotpCodeVerifier counted =
        new TotpCodeVerifier(
            credentials,
            recoveryCodes,
            new FakeCipher(),
            TotpTestSupport.lockout(tracker),
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> counted.verify(accountId, "000000"))
        .isInstanceOf(InvalidTotpCodeException.class);
    assertThatThrownBy(() -> counted.verify(accountId, "000000"))
        .isInstanceOf(InvalidTotpCodeException.class);
    counted.verify(accountId, codeFor(currentStep));

    assertThatThrownBy(() -> counted.verify(accountId, "000000"))
        .isInstanceOf(InvalidTotpCodeException.class);
    assertThat(tracker.isLocked(CodeLockout.totpCodeKey(accountId))).isFalse();
  }

  @Test
  void aRecoveryCodeIsNotAuditedWhenItIsWrong() {
    assertThatThrownBy(() -> verifier.verify(accountId, "ABCD-EFGH-IJKL-MNOP"))
        .isInstanceOf(InvalidTotpCodeException.class);
    verify(audit, never()).write(any(), any(), any(), any(), any());
  }

  // TD-SEC-086: a secret copied from another Account's row does not decrypt, so it must not let
  // anyone in, and it must not turn into a server error either.
  @Test
  void aSecretCopiedFromAnotherAccountIsRefusedLikeAWrongCode() {
    final AccountId other = AccountId.newId();
    final byte[] theirSecret = TotpAlgorithm.newSecret();
    final TotpCredential swapped =
        TotpCredential.pending(
            accountId,
            new OrganizationId(UUID.randomUUID()),
            new FakeCipher().encrypt(other, Base32.encode(theirSecret)));
    swapped.confirm(NOW.minusSeconds(3600), 0);
    credentials.save(swapped);
    final String theirCode = TotpAlgorithm.codeAt(theirSecret, currentStep);

    assertThatThrownBy(() -> verifier.verify(accountId, theirCode))
        .isInstanceOf(InvalidTotpCodeException.class);
  }
}
