package com.clavaris.app.infrastructure.config;

/**
 * Thrown by {@link ImpersonationTokenIssuer} when the caller-supplied {@code clientId} doesn't
 * resolve to a registered {@code OAuthClient}, or resolves to one belonging to a *different*
 * Organization than the impersonated Account's own — the two cases are deliberately
 * indistinguishable to the caller, same cross-tenant-isolation discipline {@code
 * OrganizationRegisteredClientRepository} already applies to every real token request (ADR-0010 §5:
 * "structurally impossible, not policy-disallowed").
 */
final class ImpersonationClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /* package */ ImpersonationClientNotFoundException(final String clientId) {
    super("No OAuthClient " + clientId + " registered under this Account's own Organization");
  }
}
