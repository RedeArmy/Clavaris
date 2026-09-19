package com.clavaris.identity.application.usecase.impersonateaccount;

/**
 * Thrown by {@link ImpersonationTokenMinter} when a requested scope isn't in the resolved OAuth
 * Client's own {@code allowedScopes} — identity-module's own copy of {@code app}'s identical
 * exception of this name, thrown across the port boundary (see {@link ImpersonationTokenMinter}'s
 * own Javadoc for why).
 */
public final class ImpersonationScopeNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ImpersonationScopeNotAllowedException(final String clientId) {
    super("Requested scope(s) exceed what OAuthClient " + clientId + " is allowed");
  }
}
