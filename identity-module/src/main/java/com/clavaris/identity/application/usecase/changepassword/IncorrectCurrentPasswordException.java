package com.clavaris.identity.application.usecase.changepassword;

/**
 * The password typed as "current" did not match. A signed-in person mistyping it is not the
 * credential-stuffing case BR-ID-06 locks out, but the endpoint is still rate-limited per Account.
 */
public final class IncorrectCurrentPasswordException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IncorrectCurrentPasswordException() {
    super("The current password is incorrect");
  }
}
