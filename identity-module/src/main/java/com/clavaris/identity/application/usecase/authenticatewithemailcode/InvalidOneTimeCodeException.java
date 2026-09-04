package com.clavaris.identity.application.usecase.authenticatewithemailcode;

/**
 * Collapses every rejection reason (unknown account, inactive account, unknown/expired/wrong-type
 * code) into one outcome — same anti-enumeration rationale {@code InvalidCredentialsException}
 * already establishes for password login.
 */
public final class InvalidOneTimeCodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidOneTimeCodeException() {
    super("Invalid or expired sign-in code");
  }
}
