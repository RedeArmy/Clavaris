package com.clavaris.identity.application.usecase.confirmemailverification;

/**
 * A presented verification token that is unknown, already consumed, naturally expired, or not of
 * type {@code EMAIL_VERIFICATION} — all treated identically, same rationale as {@code
 * rotaterefreshtoken.InvalidRefreshTokenException}: no legitimate caller benefits from
 * distinguishing "wrong type" from "expired" in the response, and not distinguishing them avoids
 * giving an attacker a free oracle on which reason applies.
 */
public final class InvalidVerificationTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidVerificationTokenException() {
    super("Invalid or expired verification token");
  }
}
