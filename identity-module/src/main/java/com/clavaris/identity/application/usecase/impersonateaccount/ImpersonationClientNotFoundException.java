package com.clavaris.identity.application.usecase.impersonateaccount;

/**
 * Thrown by {@link ImpersonationTokenMinter} when the caller-supplied {@code clientId} doesn't
 * resolve to a registered {@code OAuthClient} belonging to the target Account's own Organization —
 * identity-module's own copy of {@code app}'s identical exception of this name, thrown across the
 * port boundary (see {@link ImpersonationTokenMinter}'s own Javadoc for why).
 */
public final class ImpersonationClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ImpersonationClientNotFoundException(final String clientId) {
    super("No OAuthClient " + clientId + " registered under this Account's own Organization");
  }
}
