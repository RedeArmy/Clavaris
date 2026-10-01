package com.clavaris.identity.application.usecase.registerwebauthncredential;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.exception.RegistrationFailedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A full successful ceremony needs a real, cryptographically valid attestation response — not
 * unit-testable without a virtual authenticator (see this feature's own PR description for the
 * manual browser verification this instead relies on). This class covers the failure paths only:
 * malformed input, and the ceremony verification itself rejecting a response.
 */
class CompleteWebAuthnRegistrationServiceTest {

  private final RelyingParty relyingParty = mock(RelyingParty.class);
  private final WebAuthnCredentialRepository credentials = mock(WebAuthnCredentialRepository.class);
  private final CompleteWebAuthnRegistrationService service =
      new CompleteWebAuthnRegistrationService(relyingParty, credentials);

  @Test
  void aMalformedCredentialJsonNeverReachesTheRelyingPartyOrTheRepository()
      throws RegistrationFailedException {
    CompleteWebAuthnRegistrationCommand command =
        new CompleteWebAuthnRegistrationCommand(
            AccountId.newId(),
            new OrganizationId(UUID.randomUUID()),
            mock(PublicKeyCredentialCreationOptions.class),
            "not valid json",
            null);

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnRegistrationException.class);

    verifyNoInteractions(credentials);
    verify(relyingParty, never()).finishRegistration(any());
  }
}
