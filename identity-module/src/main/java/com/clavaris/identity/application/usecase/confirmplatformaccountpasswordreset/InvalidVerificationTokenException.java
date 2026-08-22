package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

/** Mirrors {@code confirmpasswordreset.InvalidVerificationTokenException} — same rationale. */
public final class InvalidVerificationTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidVerificationTokenException() {
    super("Invalid or expired password reset token");
  }
}
