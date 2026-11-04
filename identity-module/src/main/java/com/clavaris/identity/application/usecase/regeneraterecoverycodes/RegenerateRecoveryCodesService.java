package com.clavaris.identity.application.usecase.regeneraterecoverycodes;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpCodeVerifier;
import com.clavaris.identity.domain.model.TotpCredential;
import com.clavaris.identity.domain.service.RecoveryCodeFormat;
import java.util.List;
import org.springframework.transaction.support.TransactionOperations;

/**
 * ADR-0040. A new set ends the old one, so a printed sheet that was lost can be cancelled. It asks
 * for the second factor like turning the app off does, for the same reason.
 */
public class RegenerateRecoveryCodesService implements RegenerateRecoveryCodesUseCase {

  private final TotpCodeVerifier verifier;
  private final TotpCredentialRepository credentials;
  private final TotpRecoveryCodeRepository recoveryCodes;
  private final AuditEventRecorder auditEvents;
  private final TransactionOperations transaction;

  public RegenerateRecoveryCodesService(
      final TotpCodeVerifier verifier,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final AuditEventRecorder auditEvents,
      final TransactionOperations transaction) {
    this.verifier = verifier;
    this.credentials = credentials;
    this.recoveryCodes = recoveryCodes;
    this.auditEvents = auditEvents;
    this.transaction = transaction;
  }

  @Override
  public List<String> handle(final RegenerateRecoveryCodesCommand command) {
    final TotpCredential credential =
        credentials
            .findByAccountId(command.accountId())
            .filter(TotpCredential::isConfirmed)
            .orElseThrow(InvalidTotpCodeException::new);
    final RecoveryCodeFormat.Set fresh = RecoveryCodeFormat.newSet();
    transaction.executeWithoutResult(
        status -> {
          verifier.verify(command.accountId(), command.code());
          recoveryCodes.replaceAll(
              command.accountId(), credential.organizationId(), fresh.hashes());
          auditEvents.write(
              AuditActor.account(command.accountId().value()),
              "account.totp_recovery_codes_regenerated",
              "Account",
              command.accountId().value().toString(),
              null);
        });
    return fresh.codes();
  }
}
