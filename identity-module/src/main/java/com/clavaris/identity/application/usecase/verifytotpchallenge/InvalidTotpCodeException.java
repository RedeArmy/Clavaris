package com.clavaris.identity.application.usecase.verifytotpchallenge;

/**
 * The code is not a valid authenticator code or unspent recovery code. One type for every reason,
 * so a caller cannot tell a wrong code from a replayed one or from a malformed one.
 */
public final class InvalidTotpCodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidTotpCodeException() {
    super("Invalid authenticator code");
  }
}
