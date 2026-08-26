package com.clavaris.app.infrastructure.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;

/**
 * Defense in depth for {@code /o/{organizationId}/userinfo}'s own Bearer-token verification
 * (TD-SEC-028) — {@code NimbusJwtDecoder.withJwkSource(jwksPublishingSource)} already resolves a
 * token's signing key strictly within the CURRENT tenant's own published JWKS (a token signed under
 * a different Organization's key pair has no matching {@code kid} here at all, so signature
 * verification alone already rejects it structurally, same "structurally impossible, not
 * policy-disallowed" bar as {@link OrganizationRegisteredClientRepository}). This validator is the
 * explicit, auditable version of that same guarantee, checked against the one claim ({@code iss})
 * that actually states which Organization a token claims to belong to — matching this codebase's
 * own established pattern of never relying solely on a structural property when a real claim exists
 * to check it against (same reasoning as {@code TenantAccountOnlySecurityContextFilter}).
 *
 * <p>{@code AuthorizationServerContextHolder}, not a value captured at {@code JwtDecoder}
 * construction time — the decoder is built once per chain, but the expected issuer is per-request
 * (multi-tenant, {@code multipleIssuersAllowed(true)}), the same reason {@link
 * CurrentOrganizationContext} reads it fresh on every call rather than caching it.
 */
final class OrganizationJwtIssuerValidator implements OAuth2TokenValidator<Jwt> {

  // Constructed only by OrganizationAuthorizationServerConfig's own `new
  // OrganizationJwtIssuerValidator()` call — no state to initialize, same convention as this
  // package's other stateless writers/filters (e.g. ContentSecurityPolicyHeaderWriter).
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationJwtIssuerValidator() {
    // Intentionally empty.
  }

  @Override
  public OAuth2TokenValidatorResult validate(final Jwt token) {
    final String expectedIssuer = AuthorizationServerContextHolder.getContext().getIssuer();
    final String actualIssuer = token.getIssuer() == null ? null : token.getIssuer().toString();
    final boolean matches = expectedIssuer.equals(actualIssuer);
    return matches
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(
            new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "The iss claim does not match this Organization's own issuer",
                null));
  }
}
