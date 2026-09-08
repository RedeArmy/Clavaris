package com.clavaris.identity.application.usecase.confirmnewdeviceloginalert;

/**
 * A presented "this wasn't me" token that is unknown, already consumed, naturally expired, or
 * belongs to a different {@code VerificationTokenType} — all treated identically, same rationale as
 * {@code confirmpendingsociallink.InvalidPendingSocialLinkException}.
 */
public final class InvalidNewDeviceLoginAlertException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidNewDeviceLoginAlertException() {
    super("Invalid or expired new-device login alert token");
  }
}
