package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

/**
 * Collapses every rejection reason, same anti-enumeration-adjacent posture as {@code
 * InvalidOneTimeCodeException}.
 */
public final class InvalidDeviceTrustChallengeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidDeviceTrustChallengeException() {
    super("Invalid or expired device trust code");
  }
}
