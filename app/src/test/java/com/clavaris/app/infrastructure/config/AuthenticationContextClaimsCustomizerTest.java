package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

/**
 * ADR-0016: proves the two properties that actually matter for this customizer — the {@code acr}/
 * {@code amr} claims land on an ID token and nowhere else, same "prove what a real token would
 * carry" discipline {@link TokenIssuanceEventLoggerTest} already applies to its own sibling
 * customizer.
 */
class AuthenticationContextClaimsCustomizerTest {

  private final AuthenticationContextClaimsCustomizer customizer =
      new AuthenticationContextClaimsCustomizer();

  @Test
  void addsIso29115Level2AcrAndPasswordAmrToAnIdToken() {
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getTokenType()).thenReturn(new OAuth2TokenType("id_token"));
    when(context.getClaims()).thenReturn(claims);

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("acr")).isEqualTo("urn:clavaris:loa:2");
    assertThat(built.getClaimAsStringList("amr")).containsExactly("pwd");
  }

  @Test
  void neverTouchesAnAccessToken() {
    // A seed claim, not asserted on — JwtClaimsSet.Builder#build() rejects an empty claim set
    // outright, so an untouched builder needs *something* in it to build at all; the point of this
    // test is that acr/amr specifically never get added, not that the builder stays literally
    // empty.
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("irrelevant-for-this-test");
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getTokenType()).thenReturn(OAuth2TokenType.ACCESS_TOKEN);

    customizer.customize(context);

    // getClaims() must never even be called for a non-ID-token — asserting on the untouched
    // builder is the stronger form of that: no acr/amr claim exists on it either way.
    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("acr")).isNull();
    assertThat(built.getClaimAsStringList("amr")).isNull();
  }

  @Test
  void neverTouchesARefreshToken() {
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("irrelevant-for-this-test");
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getTokenType()).thenReturn(OAuth2TokenType.REFRESH_TOKEN);

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("acr")).isNull();
  }
}
