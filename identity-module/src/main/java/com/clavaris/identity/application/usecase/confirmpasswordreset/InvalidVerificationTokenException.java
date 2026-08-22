package com.clavaris.identity.application.usecase.confirmpasswordreset;

/**
 * A presented reset token that is unknown, already consumed, naturally expired, or not of type
 * {@code PASSWORD_RESET} — all treated identically, same rationale as {@code
 * confirmemailverification.InvalidVerificationTokenException} (its own twin for the other {@code
 * VerificationToken} flow, not reused directly so each use case's exception set stays self-
 * contained, same precedent as {@code rotaterefreshtoken.InvalidRefreshTokenException}).
 */
public final class InvalidVerificationTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidVerificationTokenException() {
    super("Invalid or expired password reset token");
  }
}
