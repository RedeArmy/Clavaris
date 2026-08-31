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

  // Code review finding: a losing confirmation in the two-active-links race
  // (ConfirmPendingSocialLinkService's own Javadoc) surfaces as a real constraint violation, not
  // an absent/expired lookup — preserved as the cause for anyone debugging via logs/traces,
  // while still presenting the same "invalid or expired" outcome to the account holder.
  public InvalidPendingSocialLinkException(final Throwable cause) {
    super("Invalid or expired social link confirmation token", cause);
  }
}
