package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.exception.AssertionFailedException;
import org.junit.jupiter.api.Test;

/**
 * A full successful ceremony needs a real, cryptographically valid assertion response — not
 * unit-testable without a virtual authenticator (see this feature's own PR description for the
 * manual browser verification this instead relies on). This class covers the failure paths only.
 */
class AuthenticateWithWebAuthnServiceTest {

  private final RelyingParty relyingParty = mock(RelyingParty.class);
  private final WebAuthnCredentialRepository credentials = mock(WebAuthnCredentialRepository.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AuthenticateWithWebAuthnService service =
      new AuthenticateWithWebAuthnService(relyingParty, credentials, accounts);

  @Test
  void aMalformedCredentialJsonNeverReachesTheRelyingPartyOrAnyRepository()
      throws AssertionFailedException {
    AuthenticateWithWebAuthnCommand command =
        new AuthenticateWithWebAuthnCommand(mock(AssertionRequest.class), "not valid json");

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnAssertionException.class);

    verifyNoInteractions(credentials, accounts);
    verify(relyingParty, never()).finishAssertion(any());
  }
}
