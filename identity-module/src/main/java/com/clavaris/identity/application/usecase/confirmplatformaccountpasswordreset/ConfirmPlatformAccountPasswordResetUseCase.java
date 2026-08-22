package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;

@FunctionalInterface
public interface ConfirmPlatformAccountPasswordResetUseCase {

  /**
   * @throws InvalidVerificationTokenException if the presented token can't be honored
   * @throws WeakPasswordException if {@code command.newRawPassword()} doesn't satisfy {@code
   *     PasswordPolicy}
   */
  void handle(ConfirmPlatformAccountPasswordResetCommand command);
}
