package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class OidcRequestParametersTest {

  @Test
  void aRegisteredUriIsNeverEncodedTwiceWhenParametersAreAppended() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("error", "login_required");
    parameters.put("state", "a b&c=d");

    assertThat(
            OidcRequestParameters.withQuery("https://app.example.com/cb?tenant=a%20b", parameters))
        .isEqualTo(
            "https://app.example.com/cb?tenant=a%20b&error=login_required&state=a%20b%26c%3Dd");
  }

  @Test
  void aMissingValueIsSkippedAndTheFirstParameterStartsTheQuery() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("error", "invalid_request");
    parameters.put("state", null);

    assertThat(OidcRequestParameters.withQuery("https://app.example.com/cb", parameters))
        .isEqualTo("https://app.example.com/cb?error=invalid_request");
  }

  @Test
  void onlyANavigationOrAClientWithoutFetchMetadataMayChangeTheEndUsersState() {
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination(null))).isTrue();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("document"))).isTrue();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("iframe"))).isTrue();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("image"))).isFalse();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("script"))).isFalse();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("empty"))).isFalse();
  }

  private static MockHttpServletRequest requestWithDestination(final String destination) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    if (destination != null) {
      request.addHeader("Sec-Fetch-Dest", destination);
    }
    return request;
  }

  // prompt=login and max_age are satisfied by "a login newer than the note", read from the latest
  // factor of the authentication. Every way of signing in puts a FactorGrantedAuthority with the
  // instant of that sign-in on the context (password, social, magic link, passkey; a device-trust
  // step-up adds another), so each of them counts, and the newest one wins.
  @Test
  void theLatestFactorOfAnyLoginMethodIsTheAuthenticationInstant() {
    Instant t0 = Instant.parse("2026-10-13T12:00:00Z");
    for (String factor :
        List.of(
            FactorGrantedAuthority.PASSWORD_AUTHORITY,
            FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY,
            FactorGrantedAuthority.OTT_AUTHORITY,
            FactorGrantedAuthority.WEBAUTHN_AUTHORITY)) {
      Authentication signedIn =
          UsernamePasswordAuthenticationToken.authenticated(
              "account",
              null,
              List.of(
                  FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                      .issuedAt(t0)
                      .build(),
                  FactorGrantedAuthority.withAuthority(factor).issuedAt(t0.plusSeconds(90)).build(),
                  new SimpleGrantedAuthority("ROLE_ACCOUNT")));

      assertThat(OidcRequestParameters.latestFactorAt(signedIn))
          .as(factor)
          .isEqualTo(t0.plusSeconds(90));
    }
  }

  // Whatever a caller redirects to is taken from the registered list itself.
  @Test
  void aRegisteredUriIsMatchedExactlyAndReturnedFromTheList() {
    java.util.List<String> registered = java.util.List.of("https://app.example.com/cb");

    assertThat(OidcRequestParameters.registeredMatch(registered, "https://app.example.com/cb"))
        .containsSame(registered.get(0));
    assertThat(OidcRequestParameters.registeredMatch(registered, "https://app.example.com/cb/"))
        .isEmpty();
    assertThat(OidcRequestParameters.registeredMatch(registered, "https://APP.example.com/cb"))
        .isEmpty();
    assertThat(OidcRequestParameters.registeredMatch(registered, null)).isEmpty();
  }
}
