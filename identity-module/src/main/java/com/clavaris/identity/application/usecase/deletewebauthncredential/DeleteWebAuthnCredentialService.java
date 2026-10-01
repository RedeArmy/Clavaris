package com.clavaris.identity.application.usecase.deletewebauthncredential;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;

public class DeleteWebAuthnCredentialService implements DeleteWebAuthnCredentialUseCase {

  private final WebAuthnCredentialRepository credentials;
  private final AuditEventRecorder auditEvents;

  public DeleteWebAuthnCredentialService(
      final WebAuthnCredentialRepository credentials, final AuditEventRecorder auditEvents) {
    this.credentials = credentials;
    this.auditEvents = auditEvents;
  }

  @Override
  public boolean handle(final DeleteWebAuthnCredentialCommand command) {
    final boolean deleted =
        credentials.deleteByIdAndAccountId(command.credentialId(), command.accountId());
    // TD-SEC-034: only a real deletion is audited — a "not found or not yours" outcome is a
    // no-op, nothing actually happened to record.
    if (deleted) {
      auditEvents.write(
          command.actor(),
          "webauthn_credential.deleted",
          "WebAuthnCredential",
          command.credentialId().toString(),
          null);
    }
    return deleted;
  }
}
