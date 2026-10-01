package com.clavaris.identity.application.usecase.deletewebauthncredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeleteWebAuthnCredentialServiceTest {

  private final WebAuthnCredentialRepository credentials = mock(WebAuthnCredentialRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final DeleteWebAuthnCredentialService service =
      new DeleteWebAuthnCredentialService(credentials, auditEvents);

  @Test
  void aSuccessfulDeleteIsAudited() {
    AccountId accountId = AccountId.newId();
    UUID credentialId = UUID.randomUUID();
    when(credentials.deleteByIdAndAccountId(credentialId, accountId)).thenReturn(true);

    boolean result = service.handle(new DeleteWebAuthnCredentialCommand(credentialId, accountId));

    assertThat(result).isTrue();
    verify(auditEvents)
        .write(
            any(),
            eq("webauthn_credential.deleted"),
            eq("WebAuthnCredential"),
            eq(credentialId.toString()),
            any());
  }

  // "Not found or not yours" — same safe no-op posture DeleteWebAuthnCredentialUseCase's own
  // Javadoc establishes: nothing actually happened, so nothing is audited either.
  @Test
  void anOwnershipMismatchOrMissingCredentialIsNeverAudited() {
    AccountId accountId = AccountId.newId();
    UUID credentialId = UUID.randomUUID();
    when(credentials.deleteByIdAndAccountId(credentialId, accountId)).thenReturn(false);

    boolean result = service.handle(new DeleteWebAuthnCredentialCommand(credentialId, accountId));

    assertThat(result).isFalse();
    verifyNoInteractions(auditEvents);
  }
}
