package com.clavaris.identity.application.usecase.disabletotp;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpCodeVerifier;
import com.clavaris.identity.domain.event.AccountTotpDisabledEvent;
import com.clavaris.identity.domain.model.OrganizationId;
import org.springframework.transaction.support.TransactionOperations;

/**
 * ADR-0040. Turning the second factor off asks for the second factor: a session someone walked up
 * to, or stole, must not be able to take the protection away by itself.
 */
public class DisableTotpService implements DisableTotpUseCase {

  private final TotpCodeVerifier verifier;
  private final TotpCredentialRepository credentials;
  private final TotpRecoveryCodeRepository recoveryCodes;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;
  private final TransactionOperations transaction;

  public DisableTotpService(
      final TotpCodeVerifier verifier,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final TransactionOperations transaction) {
    this.verifier = verifier;
    this.credentials = credentials;
    this.recoveryCodes = recoveryCodes;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.transaction = transaction;
  }

  @Override
  public void handle(final DisableTotpCommand command) {
    transaction.executeWithoutResult(
        status -> {
          verifier.verify(command.accountId(), command.code());
          // A code was just accepted, so the confirmed credential is there to read.
          final OrganizationId organizationId =
              credentials.findByAccountId(command.accountId()).orElseThrow().organizationId();
          credentials.deleteByAccountId(command.accountId());
          recoveryCodes.deleteAll(command.accountId());
          auditEvents.write(
              AuditActor.account(command.accountId().value()),
              "account.totp_disabled",
              "Account",
              command.accountId().value().toString(),
              null);
          outbox.write(
              "account.totp_disabled",
              command.accountId(),
              organizationId,
              AccountTotpDisabledEvent.of(command.accountId(), organizationId));
        });
  }
}
