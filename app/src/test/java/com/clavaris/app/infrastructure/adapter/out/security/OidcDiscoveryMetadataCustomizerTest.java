package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.oidc.OidcProviderConfiguration;

class OidcDiscoveryMetadataCustomizerTest {

  private static OidcProviderConfiguration customized() {
    final OidcProviderConfiguration.Builder builder =
        OidcProviderConfiguration.builder()
            .issuer("https://issuer.example/o/1")
            .authorizationEndpoint("https://issuer.example/o/1/oauth2/authorize")
            .tokenEndpoint("https://issuer.example/o/1/oauth2/token")
            .jwkSetUrl("https://issuer.example/o/1/oauth2/jwks")
            .responseType("code")
            .subjectType("public")
            .idTokenSigningAlgorithm("RS256");
    OidcDiscoveryMetadataCustomizer.customize(builder);
    return builder.build();
  }

  @Test
  void advertisesTheTwoAssuranceLevelsTheAcrClaimCanCarry() {
    assertThat(customized().getClaimAsStringList("acr_values_supported"))
        .containsExactly("urn:clavaris:loa:2", "urn:clavaris:loa:3");
  }

  @Test
  void saysOutrightThatTheClaimsParameterIsNotSupported() {
    assertThat(customized().<Boolean>getClaim("claims_parameter_supported")).isFalse();
  }

  @Test
  void keepsAdvertisingOnlySecretBasedClientAuthentication() {
    assertThat(customized().getTokenEndpointAuthenticationMethods())
        .isEqualTo(List.of("client_secret_basic", "client_secret_post"));
  }
}
