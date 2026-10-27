package com.clavaris.identity.application.usecase.changepassword;

/** Inbound port for the self-service "change password" action (TD-FUT-043). */
@FunctionalInterface
public interface ChangePasswordUseCase {

  /**
   * @throws IncorrectCurrentPasswordException if the current password does not match
   * @throws NoPasswordToChangeException if the Account has no password (it signs in another way)
   * @throws com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException if the
   *     new password does not satisfy {@code PasswordPolicy}
   * @throws com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException if
   *     the new password appears in a known breach (BR-ID-07)
   * @throws com.clavaris.identity.application.usecase.authenticatewithpassword
   *     .VerificationOverloadedException if password hashing is saturated and the check never ran
   */
  void handle(ChangePasswordCommand command);
}
