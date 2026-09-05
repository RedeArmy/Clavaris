package com.clavaris.identity.application.usecase.requestdevicetrustchallenge;

@FunctionalInterface
public interface RequestDeviceTrustChallengeUseCase {

  void handle(RequestDeviceTrustChallengeCommand command);
}
