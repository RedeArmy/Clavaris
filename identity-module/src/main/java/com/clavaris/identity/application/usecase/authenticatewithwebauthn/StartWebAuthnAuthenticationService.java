package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;

public class StartWebAuthnAuthenticationService implements StartWebAuthnAuthenticationUseCase {

  private final RelyingParty relyingParty;

  public StartWebAuthnAuthenticationService(final RelyingParty relyingParty) {
    this.relyingParty = relyingParty;
  }

  @Override
  public AssertionRequest handle() {
    return relyingParty.startAssertion(StartAssertionOptions.builder().build());
  }
}
