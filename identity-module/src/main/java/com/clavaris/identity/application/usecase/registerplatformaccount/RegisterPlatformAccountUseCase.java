package com.clavaris.identity.application.usecase.registerplatformaccount;

import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * RegisterPlatformAccountService} directly.
 */
@FunctionalInterface
public interface RegisterPlatformAccountUseCase {

  /**
   * @throws WeakPasswordException if {@code command.rawPassword()} doesn't satisfy {@code
   *     PasswordPolicy}
   * @throws PlatformAccountEmailAlreadyRegisteredException if the email is already registered
   */
  PlatformAccountId handle(RegisterPlatformAccountCommand command);
}
