package com.clavaris.identity.application.usecase.revokeaccountsession;

/**
 * Deliberately the same outcome whether {@code sessionId} doesn't exist at all or exists but
 * belongs to a different {@code Account} — {@link RevokeAccountSessionService}'s own ownership
 * check can't distinguish the two without leaking "that session id belongs to someone else" to
 * whoever submitted it, the same anti-enumeration shape {@code InvalidCredentialsException} and
 * {@code InvalidPendingSocialLinkException} already establish elsewhere in this module.
 */
public final class SessionNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SessionNotFoundException(final String sessionId) {
    super("No active session found with id " + sessionId);
  }
}
