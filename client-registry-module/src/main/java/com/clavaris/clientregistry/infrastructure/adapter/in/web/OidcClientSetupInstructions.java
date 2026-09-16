package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.UUID;

/**
 * Everything a consuming application's own OIDC client library needs to integrate against one
 * {@code Organization}'s issuer — rendered once, right after {@link PlatformOAuthClientController}
 * registers (or rotates the secret of) an {@link OAuthClient}, the same "copy this now, here's
 * exactly where it goes" moment a comparable IdP dashboard (Clerk, Auth0) gives an operator instead
 * of leaving them to reconstruct these endpoint shapes from API docs. Every URL here is built from
 * the same path literals {@code OrganizationAuthorizationServerConfig} itself configures ({@code
 * /oauth2/authorize}, {@code /oauth2/token}, {@code /oauth2/jwks}, {@code /userinfo}, {@code
 * /connect/logout}), not duplicated guesses — a change there must be mirrored here by hand, same as
 * every other place in this codebase that already hardcodes one of these suffixes.
 */
// PMD.LongVariable: authorizationEndpoint/userInfoEndpoint/etc. are the exact OIDC discovery
// document field names, not arbitrarily long — same precedent as
// RegisterOAuthClientRequest's own postLogoutRedirectUris suppression.
@SuppressWarnings("PMD.LongVariable")
record OidcClientSetupInstructions(
    String issuer,
    String discoveryUrl,
    String authorizationEndpoint,
    String tokenEndpoint,
    String userInfoEndpoint,
    String jwksEndpoint,
    String logoutEndpoint,
    List<String> redirectUris,
    List<String> allowedScopes,
    List<String> allowedGrantTypes) {

  /* package */ static OidcClientSetupInstructions from(
      final UUID organizationId, final String clavarisBaseUrl, final OAuthClient client) {
    final String issuer = clavarisBaseUrl.replaceAll("/+$", "") + "/o/" + organizationId;
    return new OidcClientSetupInstructions(
        issuer,
        issuer + "/.well-known/openid-configuration",
        issuer + "/oauth2/authorize",
        issuer + "/oauth2/token",
        issuer + "/userinfo",
        issuer + "/oauth2/jwks",
        issuer + "/connect/logout",
        client.redirectUris(),
        client.allowedScopes(),
        client.allowedGrantTypes());
  }
}
