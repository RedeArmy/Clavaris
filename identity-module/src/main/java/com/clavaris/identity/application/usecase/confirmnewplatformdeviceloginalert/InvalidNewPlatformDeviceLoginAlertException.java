package com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert;

/**
 * Same rationale as {@code confirmnewdeviceloginalert.InvalidNewDeviceLoginAlertException}'s own
 * tenant-tier sibling — an unknown, already-consumed, expired, or wrong-{@code
 * VerificationTokenType} token, all treated identically.
 */
public final class InvalidNewPlatformDeviceLoginAlertException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidNewPlatformDeviceLoginAlertException() {
    super("Invalid or expired new-device login alert token");
  }
}
