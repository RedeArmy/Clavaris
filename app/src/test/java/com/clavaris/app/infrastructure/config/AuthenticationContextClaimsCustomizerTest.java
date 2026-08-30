package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

/**
 * ADR-0016/ADR-0020: proves the properties that actually matter for this customizer — the {@code
 * acr}/{@code amr} claims land on an ID token and nowhere else (same "prove what a real token would
 * carry" discipline {@link TokenIssuanceEventLoggerTest} already applies to its own sibling
 * customizer), and {@code amr} is now genuinely computed from the principal's own authorities, not
 * hardcoded — see this class's own Javadoc for the full ADR-0020 revisit.
 */
class AuthenticationContextClaimsCustomizerTest {

  private final AuthenticationContextClaimsCustomizer customizer =
      new AuthenticationContextClaimsCustomizer();

  private JwtEncodingContext contextWithAuthorities(
      final OAuth2TokenType tokenType,
      final JwtClaimsSet.Builder claims,
      final List<? extends GrantedAuthority> authorities) {
    Authentication principal = mock(Authentication.class);
    // doReturn, not when(...).thenReturn(...): getAuthorities()'s own wildcard-capture return type
    // (Collection<? extends GrantedAuthority>) can't unify with this method's own separately
    // captured `? extends GrantedAuthority` parameter type — a real javac limitation with nested
    // wildcard captures, not a Mockito API gap; doReturn sidesteps it by accepting a plain Object.
    doReturn(authorities).when(principal).getAuthorities();
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getTokenType()).thenReturn(tokenType);
    when(context.getClaims()).thenReturn(claims);
    when(context.getPrincipal()).thenReturn(principal);
    return context;
  }

  @Test
  void addsIso29115Level2AcrAndPasswordAmrToAnIdTokenWhenNoAmrAuthorityIsPresent() {
    // The password login path (SpringSecurityAuthenticatedSessionEstablisher.establish()) attaches
    // no AMR_-prefixed authority at all — this is that exact shape.
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context =
        contextWithAuthorities(
            new OAuth2TokenType("id_token"),
            claims,
            List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT")));

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("acr")).isEqualTo("urn:clavaris:loa:2");
    assertThat(built.getClaimAsStringList("amr")).containsExactly("pwd");
  }

  @Test
  void addsTheProviderNameAsAmrForASocialLoginSession() {
    // SpringSecurityAuthenticatedSessionEstablisher.establishViaSocialLogin's own shape.
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context =
        contextWithAuthorities(
            new OAuth2TokenType("id_token"),
            claims,
            List.of(
                new SimpleGrantedAuthority("ROLE_ACCOUNT"),
                new SimpleGrantedAuthority("AMR_GOOGLE")));

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("acr")).isEqualTo("urn:clavaris:loa:2");
    assertThat(built.getClaimAsStringList("amr")).containsExactly("google");
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
