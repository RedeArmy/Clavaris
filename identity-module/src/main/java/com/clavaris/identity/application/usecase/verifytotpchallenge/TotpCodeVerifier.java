package com.clavaris.identity.application.usecase.verifytotpchallenge;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpSecretCipher;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.TotpCredential;
import com.clavaris.identity.domain.service.Base32;
import com.clavaris.identity.domain.service.RecoveryCodeFormat;
import com.clavaris.identity.domain.service.TotpAlgorithm;
import java.time.Clock;
import java.util.OptionalLong;

/**
 * The one place a second-factor entry is judged (ADR-0040), shared by the sign-in challenge and by
 * the actions that must not be done on a stolen session alone (turning the app off, making new
 * recovery codes).
 *
 * <p>Wrong entries are counted per Account and, past the limit, the method is paused and the owner
 * told (ADR-0033): six digits are only a million guesses, so the pause is what makes them safe. A
 * code is accepted for a time step later than the last one spent, in one statement, so it works
 * once even when two requests carry it together; a recovery code is spent the same way.
 */
public class TotpCodeVerifier {

  /** Steps either side of now that are forgiven, for a phone clock that is a little off. */
  private static final int WINDOW_STEPS = 1;

  private final TotpCredentialRepository credentials;
  private final TotpRecoveryCodeRepository recoveryCodes;
  private final TotpSecretCipher cipher;
  private final CodeLockout lockout;
  private final AuditEventRecorder auditEvents;
  private final Clock clock;

  public TotpCodeVerifier(
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final TotpSecretCipher cipher,
      final CodeLockout lockout,
      final AuditEventRecorder auditEvents,
      final Clock clock) {
    this.credentials = credentials;
    this.recoveryCodes = recoveryCodes;
    this.cipher = cipher;
    this.lockout = lockout;
    this.auditEvents = auditEvents;
    this.clock = clock;
  }

  /**
   * @throws com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException if
   *     the method is paused for this Account
   * @throws InvalidTotpCodeException if the entry is not valid
   */
  public TotpMethod verify(final AccountId accountId, final String typed) {
    final String key = CodeLockout.totpCodeKey(accountId);
    lockout.ensureOpen(key);

    final TotpMethod method = judge(accountId, typed);
    if (method == null) {
      lockout.onDeviceCodeFailure(accountId, key);
      throw new InvalidTotpCodeException();
    }
    lockout.onSuccess(key);
    if (method == TotpMethod.RECOVERY_CODE) {
      auditEvents.write(
          AuditActor.account(accountId.value()),
          "account.totp_recovery_code_used",
          "Account",
          accountId.value().toString(),
          "remaining=" + recoveryCodes.countUnused(accountId));
    }
    return method;
  }

  private TotpMethod judge(final AccountId accountId, final String typed) {
    final String entry = typed == null ? "" : typed.trim();
    if (TotpAlgorithm.looksLikeCode(entry)) {
      return judgeAuthenticatorCode(accountId, entry) ? TotpMethod.AUTHENTICATOR : null;
    }
    final String normalized = RecoveryCodeFormat.normalize(entry);
    if (normalized != null
        && credentials.existsConfirmed(accountId)
        && recoveryCodes.consume(accountId, RecoveryCodeFormat.hash(normalized))) {
      return TotpMethod.RECOVERY_CODE;
    }
    return null;
  }

  private boolean judgeAuthenticatorCode(final AccountId accountId, final String code) {
    final TotpCredential credential =
        credentials.findByAccountId(accountId).filter(TotpCredential::isConfirmed).orElse(null);
    if (credential == null) {
      return false;
    }
    final byte[] secret = Base32.decode(cipher.decrypt(credential.encryptedSecret()));
    final OptionalLong step =
        TotpAlgorithm.matchingStep(secret, code, clock.instant().getEpochSecond(), WINDOW_STEPS);
    return step.isPresent()
        && credential.accepts(step.getAsLong())
        && credentials.spendStep(accountId, step.getAsLong());
  }
}
