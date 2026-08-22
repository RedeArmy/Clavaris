package com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword;

/**
 * Mirrors {@code authenticatewithpassword.InvalidCredentialsException} exactly — every failure mode
 * (unknown email, inactive account, no credential, wrong password) is one outcome from the caller's
 * point of view, for the same anti-enumeration reason.
 */
public final class InvalidPlatformCredentialsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidPlatformCredentialsException() {
    super("Invalid email or password");
  }
}
