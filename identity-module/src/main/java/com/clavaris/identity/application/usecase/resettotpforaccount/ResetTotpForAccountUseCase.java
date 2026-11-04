package com.clavaris.identity.application.usecase.resettotpforaccount;

/** ADR-0040: an operator removes an Account's authenticator app, for a person who lost it. */
@FunctionalInterface
public interface ResetTotpForAccountUseCase {

  /**
   * @return true when the Account had an authenticator app to remove
   * @throws com.clavaris.identity.application.usecase.suspendaccount.AccountNotFoundException if
   *     there is no such Account
   */
  boolean handle(ResetTotpForAccountCommand command);
}
