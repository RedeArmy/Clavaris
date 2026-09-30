package com.clavaris.identity.application.usecase.registeraccount;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link RegisterAccountService}
 * directly.
 */
@FunctionalInterface
public interface RegisterAccountUseCase {

  /**
   * @throws WeakPasswordException if {@code command.rawPassword()} doesn't satisfy {@code
   *     PasswordPolicy}
   * @throws EmailAlreadyRegisteredException if the email is already registered in this organization
   *     (BR-ORG-01 scoping)
   */
  RegisterAccountResult handle(RegisterAccountCommand command);
}
