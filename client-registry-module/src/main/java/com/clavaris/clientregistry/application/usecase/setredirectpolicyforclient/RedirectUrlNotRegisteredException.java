package com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient;

/**
 * A configured fallback/force redirect URL must be a verbatim member of the owning {@code
 * OAuthClient}'s own {@code redirectUris} allowlist — the same invariant the runtime {@code
 * redirect_url} query-param override relies on at login time, enforced once here so it can never
 * drift out of sync. Never a way to introduce an open-redirect surface via this policy.
 */
public final class RedirectUrlNotRegisteredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RedirectUrlNotRegisteredException(final String url) {
    super("Redirect URL is not a registered redirectUri for this OAuthClient: " + url);
  }
}
