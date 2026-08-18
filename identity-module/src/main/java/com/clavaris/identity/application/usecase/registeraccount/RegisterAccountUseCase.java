package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.AccountId;

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
  AccountId handle(RegisterAccountCommand command);
}
