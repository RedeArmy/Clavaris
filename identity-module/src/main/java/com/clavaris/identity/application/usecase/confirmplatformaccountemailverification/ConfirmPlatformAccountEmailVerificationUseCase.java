package com.clavaris.identity.application.usecase.confirmplatformaccountemailverification;

@FunctionalInterface
public interface ConfirmPlatformAccountEmailVerificationUseCase {

  /**
   * @throws InvalidVerificationTokenException if the presented token can't be honored
   */
  void handle(ConfirmPlatformAccountEmailVerificationCommand command);
}
