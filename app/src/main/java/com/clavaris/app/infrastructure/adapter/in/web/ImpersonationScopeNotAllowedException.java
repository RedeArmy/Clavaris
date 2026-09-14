package com.clavaris.app.infrastructure.adapter.in.web;

import com.clavaris.app.infrastructure.adapter.out.security.ImpersonationTokenIssuer;

/**
 * Thrown by {@link ImpersonationTokenIssuer} when a caller-requested scope isn't in the resolved
 * {@code OAuthClient}'s own {@code allowedScopes} — RFC 6749 §6's own "not exceeded" rule, applied
 * here the same way {@code RequestedScopeExceedsAuthorizedScopeException} applies it to a refresh
 * grant.
 */
public final class ImpersonationScopeNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ImpersonationScopeNotAllowedException(final String clientId) {
    super("Requested scope(s) exceed what OAuthClient " + clientId + " is allowed");
  }
}
