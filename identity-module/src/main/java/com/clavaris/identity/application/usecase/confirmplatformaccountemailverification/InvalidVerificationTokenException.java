package com.clavaris.identity.application.usecase.confirmplatformaccountemailverification;

/** Mirrors {@code confirmemailverification.InvalidVerificationTokenException} — same rationale. */
public final class InvalidVerificationTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidVerificationTokenException() {
    super("Invalid or expired verification token");
  }
}
