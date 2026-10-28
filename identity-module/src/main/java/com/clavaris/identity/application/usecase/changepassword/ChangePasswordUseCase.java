package com.clavaris.identity.application.usecase.changepassword;

/** Inbound port for the self-service "change password" action (TD-FUT-043). */
@FunctionalInterface
public interface ChangePasswordUseCase {

  /**
   * @throws IncorrectCurrentPasswordException if the current password does not match
   * @throws RecentSignInRequiredException if the Account has no password the person chose and its
   *     session was not signed in recently enough to stand in for one
   */
  void handle(ChangePasswordCommand command);
}
