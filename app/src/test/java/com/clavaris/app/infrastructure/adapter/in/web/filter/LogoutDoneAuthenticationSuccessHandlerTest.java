package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;

class LogoutDoneAuthenticationSuccessHandlerTest {

  private static final String ORGANIZATION = "0b6f5f0e-93c3-4c1f-9d36-2f6f5b1d3c11";
  private static final String LOGOUT_PATH = "/o/" + ORGANIZATION + "/connect/logout";

  private final LogoutDoneAuthenticationSuccessHandler handler =
      new LogoutDoneAuthenticationSuccessHandler();

  private static OidcLogoutAuthenticationToken token(
      final String postLogoutRedirectUri, final String state) {
    final Authentication principal =
        UsernamePasswordAuthenticationToken.authenticated("account", null, List.of());
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .subject("account")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    return new OidcLogoutAuthenticationToken(
        idToken, principal, "session-id", "client-id", postLogoutRedirectUri, state);
  }

  private static MockHttpServletRequest request(final String path) {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setSession(new MockHttpSession());
    return request;
  }

  @Test
  void withoutAPostLogoutRedirectUriTheEndUserSeesTheSignedOutPage() throws Exception {
    final MockHttpServletRequest request = request(LOGOUT_PATH);
    final MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, token(null, null));

    assertThat(response.getForwardedUrl()).isEqualTo("/o/" + ORGANIZATION + "/logout/done");
    assertThat(response.getRedirectedUrl()).isNull();
  }

  @Test
  void theSessionIsEndedBeforeThePageIsShown() throws Exception {
    final MockHttpServletRequest request = request(LOGOUT_PATH);
    final MockHttpSession session = (MockHttpSession) request.getSession(false);

    handler.onAuthenticationSuccess(request, new MockHttpServletResponse(), token(null, null));

    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void aBlankPostLogoutRedirectUriCountsAsNone() throws Exception {
    final MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request(LOGOUT_PATH), response, token(" ", "state"));

    assertThat(response.getForwardedUrl()).isEqualTo("/o/" + ORGANIZATION + "/logout/done");
  }

  @Test
  void aRegisteredPostLogoutRedirectUriIsStillRedirectedToWithItsState() throws Exception {
    final MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        request(LOGOUT_PATH), response, token("https://client.example.test/logged-out", "abc123"));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("https://client.example.test/logged-out?state=abc123");
    assertThat(response.getForwardedUrl()).isNull();
  }

  @Test
  void aRequestOutsideTheOrganizationLogoutPathKeepsTheDefaultRedirect() throws Exception {
    final MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request("/connect/logout"), response, token(null, null));

    assertThat(response.getRedirectedUrl()).isEqualTo("/");
    assertThat(response.getForwardedUrl()).isNull();
  }
}
