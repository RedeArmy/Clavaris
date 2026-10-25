package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BR-CLIENT-01 and the grant/scope rules, tested on their own now that they live outside
 * OAuthClient.
 */
class ClientRegistrationRulesTest {

  @Test
  void acceptsHttpsAndLoopbackHttpAndCustomSchemes() {
    final List<String> uris =
        List.of(
            "https://app.example.com/callback",
            "http://localhost:3000/cb",
            "http://127.0.0.1/cb",
            "com.example.app://callback");

    assertThat(ClientRegistrationRules.requireValidUris(uris, "redirectUris")).isEqualTo(uris);
  }

  @Test
  void rejectsPlainHttpAgainstARemoteHost() {
    assertThatThrownBy(
            () ->
                ClientRegistrationRules.requireValidUris(
                    List.of("http://app.example.com/cb"), "redirectUris"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must use https");
  }

  @Test
  void rejectsBlankRelativeAndMalformedEntries() {
    assertThatThrownBy(() -> ClientRegistrationRules.requireValidUris(List.of(" "), "redirectUris"))
        .hasMessageContaining("blank entry");
    assertThatThrownBy(
            () -> ClientRegistrationRules.requireValidUris(List.of("/relative"), "redirectUris"))
        .hasMessageContaining("absolute");
    assertThatThrownBy(
            () -> ClientRegistrationRules.requireValidUris(List.of("http://a b"), "redirectUris"))
        .hasMessageContaining("well-formed");
  }

  @Test
  void anEmptyUriListIsValidAndANullOneIsNot() {
    assertThat(ClientRegistrationRules.requireValidUris(List.of(), "redirectUris")).isEmpty();
    assertThatThrownBy(() -> ClientRegistrationRules.requireValidUris(null, "redirectUris"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsAnUnknownGrantTypeAndAnEmptyGrantList() {
    assertThatThrownBy(() -> ClientRegistrationRules.requireKnownGrantTypes(List.of("password")))
        .hasMessageContaining("known grant types");
    assertThatThrownBy(
            () -> ClientRegistrationRules.requireNonEmpty(List.of(), "allowedGrantTypes"))
        .hasMessageContaining("must not be empty");
  }

  @Test
  void rejectsAReservedPlatformScopeButAllowsConsumerDefinedOnes() {
    assertThat(ClientRegistrationRules.requireValidScopes(List.of("test.read", "openid")))
        .containsExactly("test.read", "openid");
    assertThatThrownBy(
            () ->
                ClientRegistrationRules.requireValidScopes(List.of("platform:organizations:write")))
        .hasMessageContaining("reserved platform");
  }
}
