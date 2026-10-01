package com.clavaris.identity.application.usecase.registerwebauthncredential;

/**
 * Thrown for any failure in the WebAuthn registration ceremony (expired/replayed challenge, failed
 * attestation verification, or no pending registration in session).
 */
public final class InvalidWebAuthnRegistrationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidWebAuthnRegistrationException(final String message, final Throwable cause) {
    super(message, cause);
  }

  public InvalidWebAuthnRegistrationException(final String message) {
    super(message);
  }
}
