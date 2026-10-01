package com.clavaris.identity.application.usecase.registerwebauthncredential;

@FunctionalInterface
public interface CompleteWebAuthnRegistrationUseCase {

  /**
   * @throws InvalidWebAuthnRegistrationException on any ceremony failure.
   */
  void handle(CompleteWebAuthnRegistrationCommand command);
}
