package com.clavaris.identity.application.usecase.revokeplatformaccountsession;

/**
 * TD-FUT-026: platform-tier mirror of {@code revokeaccountsession.SessionNotFoundException} — same
 * anti-enumeration shape (a nonexistent id and one owned by a different {@code PlatformAccount}
 * produce the exact same outcome).
 */
public final class PlatformAccountSessionNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformAccountSessionNotFoundException(final String sessionId) {
    super("No active session found with id " + sessionId);
  }
}
