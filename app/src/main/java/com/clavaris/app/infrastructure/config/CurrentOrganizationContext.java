package com.clavaris.app.infrastructure.config;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;

/**
 * Parses the current request's {@code organizationId} back out of the tenant issuer Spring
 * Authorization Server's own {@code AuthorizationServerContextFilter} resolves per-request
 * (ADR-0010 §5's {@code {clavarisBaseUrl}/o/{organizationId}} shape). Confirmed live by decompiling
 * the actual resolved SAS 7.1.0 jar (spike 0001's Appendix C addendum): that filter runs
 * immediately after {@code SecurityContextHolderFilter}, before any client-authentication filter,
 * so {@link AuthorizationServerContextHolder} is reliably populated for the rest of the chain.
 *
 * <p>Shared by {@link OrganizationScopedJwkSource} and {@link
 * OrganizationRegisteredClientRepository} — the two places on the dynamic per-Organization issuer
 * that need to know "which Organization is this request for" without SAS itself exposing that as a
 * first-class concept.
 */
final class CurrentOrganizationContext {

  // ADR-0010 §5's fixed issuer shape — the one place this literal is allowed to live; both this
  // class and OrganizationAuthorizationServerConfig's securityMatcher must agree on it.
  private static final String PREFIX = "/o/";

  private CurrentOrganizationContext() {
    // Static utility — not instantiable.
  }

  /**
   * Empty, never thrown, for anything that doesn't look like a real Organization issuer — a
   * malformed or unknown path segment must surface as "no key"/"no client found" to the caller
   * (invalid_client, or an empty JWK set), never a raw 500 from deep inside a JWKSource or
   * RegisteredClientRepository. Three independent "this isn't a real Organization issuer" exits are
   * clearer as early guard clauses than one accumulated through nested branching.
   */
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ static Optional<UUID> currentOrganizationId() {
    final String issuer = AuthorizationServerContextHolder.getContext().getIssuer();
    if (issuer == null) {
      return Optional.empty();
    }
    final int prefixIndex = issuer.lastIndexOf(PREFIX);
    if (prefixIndex < 0) {
      return Optional.empty();
    }
    final String segment = issuer.substring(prefixIndex + PREFIX.length());
    try {
      return Optional.of(UUID.fromString(segment));
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
