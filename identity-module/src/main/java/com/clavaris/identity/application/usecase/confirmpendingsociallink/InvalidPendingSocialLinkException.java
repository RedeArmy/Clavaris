package com.clavaris.identity.application.usecase.confirmpendingsociallink;

/**
 * A presented confirmation token that is unknown, already consumed, or naturally expired — all
 * treated identically, same rationale as {@code
 * confirmemailverification.InvalidVerificationTokenException}.
 */
public final class InvalidPendingSocialLinkException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidPendingSocialLinkException() {
    super("Invalid or expired social link confirmation token");
  }
}
