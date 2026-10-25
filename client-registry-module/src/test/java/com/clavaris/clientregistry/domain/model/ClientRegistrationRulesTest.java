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
    final List<String> uris = List.of("http://app.example.com/cb");

    assertThatThrownBy(() -> ClientRegistrationRules.requireValidUris(uris, "redirectUris"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must use https");
  }

  @Test
  void rejectsBlankRelativeAndMalformedEntries() {
    final List<String> blank = List.of(" ");
    final List<String> relative = List.of("/relative");
    final List<String> malformed = List.of("http://a b");

    assertThatThrownBy(() -> ClientRegistrationRules.requireValidUris(blank, "redirectUris"))
        .hasMessageContaining("blank entry");
    assertThatThrownBy(() -> ClientRegistrationRules.requireValidUris(relative, "redirectUris"))
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> ClientRegistrationRules.requireValidUris(malformed, "redirectUris"))
        .hasMessageContaining("well-formed");
  }

  @Test
  void anEmptyUriListIsValidAndANullOneIsNot() {
    final List<String> none = List.of();

    assertThat(ClientRegistrationRules.requireValidUris(none, "redirectUris")).isEmpty();
    assertThatThrownBy(() -> ClientRegistrationRules.requireValidUris(null, "redirectUris"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsAnUnknownGrantTypeAndAnEmptyGrantList() {
    final List<String> unknown = List.of("password");
    final List<String> none = List.of();

    assertThatThrownBy(() -> ClientRegistrationRules.requireKnownGrantTypes(unknown))
        .hasMessageContaining("known grant types");
    assertThatThrownBy(() -> ClientRegistrationRules.requireNonEmpty(none, "allowedGrantTypes"))
        .hasMessageContaining("must not be empty");
  }

  @Test
  void rejectsAReservedPlatformScopeButAllowsConsumerDefinedOnes() {
    final List<String> consumerScopes = List.of("test.read", "openid");
    final List<String> reserved = List.of("platform:organizations:write");

    assertThat(ClientRegistrationRules.requireValidScopes(consumerScopes))
        .containsExactly("test.read", "openid");
    assertThatThrownBy(() -> ClientRegistrationRules.requireValidScopes(reserved))
        .hasMessageContaining("reserved platform");
  }
}
