package com.clavaris.identity.application.usecase.confirmemailchange;

@FunctionalInterface
public interface ConfirmEmailChangeUseCase {

  /**
   * @throws InvalidEmailChangeTokenException unknown, expired or already used link
   * @throws
   *     com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException
   *     the address was taken by someone else after the request was made
   */
  void handle(ConfirmEmailChangeCommand command);
}
