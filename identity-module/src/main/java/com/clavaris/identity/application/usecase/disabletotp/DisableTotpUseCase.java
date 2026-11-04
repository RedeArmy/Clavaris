package com.clavaris.identity.application.usecase.disabletotp;

/** ADR-0040: the Account turns its authenticator app off. */
@FunctionalInterface
public interface DisableTotpUseCase {

  /**
   * @throws com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException
   *     if the code is wrong
   * @throws com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException if
   *     the method is paused
   */
  void handle(DisableTotpCommand command);
}
