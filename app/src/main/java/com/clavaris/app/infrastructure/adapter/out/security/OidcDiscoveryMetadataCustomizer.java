package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.clientregistry.domain.model.OidcScopeCatalog;
import java.util.List;
import org.springframework.security.oauth2.server.authorization.oidc.OidcProviderConfiguration;

/**
 * Makes the discovery document ({@code /.well-known/openid-configuration}) say what Clavaris really
 * does, instead of Spring Authorization Server's generic defaults (OpenID Connect Discovery 1.0 §3;
 * RFC 8414 §2).
 *
 * <ul>
 *   <li>{@code scopes_supported} and {@code claims_supported} were missing or just {@code openid}:
 *       now the scopes {@link OidcScopeCatalog} defines and the claims the ID token and {@code
 *       /userinfo} actually carry.
 *   <li>{@code token_endpoint_auth_methods_supported} (and the revocation and introspection
 *       equivalents) listed {@code private_key_jwt}, {@code client_secret_jwt} and the mTLS
 *       methods. Every client is registered with {@code client_secret_basic} and {@code
 *       client_secret_post} only, so those are the only ones advertised.
 *   <li>{@code tls_client_certificate_bound_access_tokens} and {@code
 *       dpop_signing_alg_values_supported} announced sender-constrained tokens, which Clavaris does
 *       not issue: removed.
 * </ul>
 */
public final class OidcDiscoveryMetadataCustomizer {

  private static final List<String> CLAIMS_SUPPORTED =
      List.of(
          "sub",
          "iss",
          "aud",
          "exp",
          "iat",
          "auth_time",
          "nonce",
          "acr",
          "amr",
          "sid",
          "azp",
          "email",
          "email_verified",
          "phone_number",
          "phone_number_verified",
          "name",
          "given_name",
          "family_name",
          "preferred_username",
          "picture",
          "workspace_id",
          "workspace_role",
          "workspace_permissions");

  private static final List<String> CLIENT_AUTH_METHODS =
      List.of("client_secret_basic", "client_secret_post");

  private OidcDiscoveryMetadataCustomizer() {
    // Static customizer, never instantiated.
  }

  public static void customize(final OidcProviderConfiguration.Builder builder) {
    builder
        .scopes(
            scopes -> {
              scopes.clear();
              scopes.addAll(OidcScopeCatalog.KNOWN);
            })
        .claim("claims_supported", CLAIMS_SUPPORTED)
        .claim("request_parameter_supported", false)
        .claim("request_uri_parameter_supported", false)
        .tokenEndpointAuthenticationMethods(OidcDiscoveryMetadataCustomizer::onlySecretBased)
        .tokenRevocationEndpointAuthenticationMethods(
            OidcDiscoveryMetadataCustomizer::onlySecretBased)
        .tokenIntrospectionEndpointAuthenticationMethods(
            OidcDiscoveryMetadataCustomizer::onlySecretBased)
        .claims(
            claims -> {
              claims.remove("tls_client_certificate_bound_access_tokens");
              claims.remove("dpop_signing_alg_values_supported");
            });
  }

  private static void onlySecretBased(final List<String> methods) {
    methods.clear();
    methods.addAll(CLIENT_AUTH_METHODS);
  }
}
