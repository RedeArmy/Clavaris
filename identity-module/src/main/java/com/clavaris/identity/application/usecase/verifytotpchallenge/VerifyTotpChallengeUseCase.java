package com.clavaris.identity.application.usecase.verifytotpchallenge;

/** ADR-0040: checks the second factor asked for at sign-in. */
@FunctionalInterface
public interface VerifyTotpChallengeUseCase {

  /**
   * @throws InvalidTotpCodeException if the entry is not valid
   * @throws com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException if
   *     the method is paused
   */
  TotpMethod handle(VerifyTotpChallengeCommand command);
}
