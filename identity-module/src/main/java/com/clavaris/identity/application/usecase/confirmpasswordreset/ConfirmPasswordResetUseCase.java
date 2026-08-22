package com.clavaris.identity.application.usecase.confirmpasswordreset;

import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * ConfirmPasswordResetService} directly.
 */
@FunctionalInterface
public interface ConfirmPasswordResetUseCase {

  /**
   * @throws InvalidVerificationTokenException if the presented token can't be honored
   * @throws WeakPasswordException if {@code command.newRawPassword()} doesn't satisfy {@code
   *     PasswordPolicy}
   */
  void handle(ConfirmPasswordResetCommand command);
}
