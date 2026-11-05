package com.clavaris.identity.application.usecase.confirmtotpenrollment;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpSecretCipher;
import com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException;
import com.clavaris.identity.domain.event.AccountTotpEnrolledEvent;
import com.clavaris.identity.domain.model.TotpCredential;
import com.clavaris.identity.domain.service.Base32;
import com.clavaris.identity.domain.service.RecoveryCodeFormat;
import com.clavaris.identity.domain.service.TotpAlgorithm;
import java.time.Clock;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.transaction.support.TransactionOperations;

/**
 * ADR-0040. Confirming is what turns a stored secret into a second factor, and only the person who
 * can read the code off their app can do it, so a secret nobody added is never asked for at
 * sign-in. The step of the confirming code is spent, so it cannot be replayed to sign in a moment
 * later, and the recovery codes are made in the same transaction that confirms.
 */
public class ConfirmTotpEnrollmentService implements ConfirmTotpEnrollmentUseCase {

  private static final int WINDOW_STEPS = 1;

  private final TotpCredentialRepository credentials;
  private final TotpRecoveryCodeRepository recoveryCodes;
  private final TotpSecretCipher cipher;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;
  private final Clock clock;
  private final TransactionOperations transaction;

  public ConfirmTotpEnrollmentService(
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final TotpSecretCipher cipher,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final Clock clock,
      final TransactionOperations transaction) {
    this.credentials = credentials;
    this.recoveryCodes = recoveryCodes;
    this.cipher = cipher;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.clock = clock;
    this.transaction = transaction;
  }

  @Override
  public List<String> handle(final ConfirmTotpEnrollmentCommand command) {
    final TotpCredential credential =
        credentials
            .findByAccountId(command.accountId())
            .filter(found -> !found.isConfirmed())
            .orElseThrow(InvalidTotpCodeException::new);
    final String typed = command.code() == null ? "" : command.code().trim();
    if (!TotpAlgorithm.looksLikeCode(typed)) {
      throw new InvalidTotpCodeException();
    }
    final byte[] secret = Base32.decode(cipher.decrypt(credential.encryptedSecret()));
    final OptionalLong step =
        TotpAlgorithm.matchingStep(secret, typed, clock.instant().getEpochSecond(), WINDOW_STEPS);
    if (step.isEmpty()) {
      throw new InvalidTotpCodeException();
    }

    final RecoveryCodeFormat.Set fresh = RecoveryCodeFormat.newSet();
    transaction.executeWithoutResult(
        status -> {
          credential.confirm(clock.instant(), step.getAsLong());
          credentials.save(credential);
          recoveryCodes.replaceAll(
              command.accountId(), credential.organizationId(), fresh.hashes());
          auditEvents.write(
              AuditActor.account(command.accountId().value()),
              "account.totp_enrolled",
              "Account",
              command.accountId().value().toString(),
              null);
          outbox.write(
              "account.totp_enrolled",
              command.accountId(),
              credential.organizationId(),
              AccountTotpEnrolledEvent.of(command.accountId(), credential.organizationId()));
        });
    return fresh.codes();
  }
}
