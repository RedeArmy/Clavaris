package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

/**
 * Thrown for any failure in the WebAuthn authentication ceremony — same anti-enumeration collapsing
 * rationale as {@code InvalidCredentialsException}: a failed assertion, an unresolvable Account, or
 * an expired/missing challenge are all indistinguishable to the caller.
 */
public final class InvalidWebAuthnAssertionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidWebAuthnAssertionException() {
    super("Invalid or expired passkey sign-in attempt");
  }
}
