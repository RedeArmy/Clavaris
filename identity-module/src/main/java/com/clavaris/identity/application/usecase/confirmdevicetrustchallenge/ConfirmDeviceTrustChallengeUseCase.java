package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

@FunctionalInterface
public interface ConfirmDeviceTrustChallengeUseCase {

  /**
   * @throws InvalidDeviceTrustChallengeException on any invalid/expired/wrong-owner code.
   */
  void handle(ConfirmDeviceTrustChallengeCommand command);
}
