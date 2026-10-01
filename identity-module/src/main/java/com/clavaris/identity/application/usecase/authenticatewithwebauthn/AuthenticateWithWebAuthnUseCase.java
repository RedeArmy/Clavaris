package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import com.clavaris.identity.domain.model.Account;

@FunctionalInterface
public interface AuthenticateWithWebAuthnUseCase {

  /**
   * @throws InvalidWebAuthnAssertionException on any ceremony failure or unresolvable Account.
   */
  Account handle(AuthenticateWithWebAuthnCommand command);
}
