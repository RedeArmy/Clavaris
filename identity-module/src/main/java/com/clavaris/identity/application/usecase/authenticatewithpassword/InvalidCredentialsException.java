package com.clavaris.identity.application.usecase.authenticatewithpassword;

/**
 * Thrown for every failure mode {@link AuthenticateWithPasswordService} can hit — unknown email, a
 * suspended/inactive account, an account with no password credential attached, or a genuinely wrong
 * password — all rejected identically, with this same message, and nothing else observable (timing
 * aside; that is out of this class's control and a separate, known hardening item). Distinguishing
 * "no such account" from "wrong password" in the response is exactly what lets an attacker
 * enumerate which emails are registered — deliberately not done here, in either the exception type
 * or its message, no matter which internal branch actually failed.
 */
public final class InvalidCredentialsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidCredentialsException() {
    super("Invalid organization, email, or password");
  }
}
