package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.infrastructure.adapter.in.web.RpInitiatedLogoutController;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A logout without id_token_hint must never create state for a visitor who has no session, and must
 * only end a signed-in End-User's session with the confirmation token their own session issued.
 */
class OidcLogoutWithoutHintFilterTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final String CLIENT = "client_abc";
  private static final String LOGOUT_URI = "https://app.example.com/bye";

  private final OAuthClientRepository clients = mock(OAuthClientRepository.class);
  private final OidcLogoutWithoutHintFilter filter = new OidcLogoutWithoutHintFilter(clients);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private void registeredClient() {
    OAuthClient client = mock(OAuthClient.class);
    when(client.active()).thenReturn(true);
    when(client.organizationId()).thenReturn(ORGANIZATION);
    when(client.postLogoutRedirectUris()).thenReturn(List.of(LOGOUT_URI));
    when(clients.findByClientId(CLIENT)).thenReturn(Optional.of(client));
  }

  private static void signedIn() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.randomUUID().toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"))));
  }

  private static MockHttpServletRequest logout(final String method) {
    MockHttpServletRequest request =
        new MockHttpServletRequest(method, "/o/" + ORGANIZATION + "/connect/logout");
    request.setParameter("client_id", CLIENT);
    request.setParameter("post_logout_redirect_uri", LOGOUT_URI);
    request.setParameter("state", "s1");
    return request;
  }

  @Test
  void aLogoutWithNobodySignedInCreatesNoSessionAndShowsTheSignedOutPage() throws Exception {
    MockHttpServletRequest request = logout("GET");
    request.setParameter("post_logout_redirect_uri", "https://evil.example/x");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(request.getSession(false)).as("no session is created").isNull();
    assertThat(response.getForwardedUrl()).isEqualTo("/o/" + ORGANIZATION + "/logout/done");
  }

  @Test
  void aLogoutWithNobodySignedInStillReturnsToARegisteredUriWithoutASession() throws Exception {
    registeredClient();
    MockHttpServletRequest request = logout("GET");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(request.getSession(false)).isNull();
    assertThat(response.getRedirectedUrl()).isEqualTo(LOGOUT_URI + "?state=s1");
  }

  @Test
  void aSignedInEndUserIsAskedToConfirm() throws Exception {
    signedIn();
    MockHttpServletRequest request = logout("GET");
    request.setSession(new MockHttpSession());
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getForwardedUrl()).isEqualTo("/o/" + ORGANIZATION + "/logout/confirm");
    assertThat(request.getSession(false)).isNotNull();
  }

  @Test
  void aWrongConfirmationTokenSignsNobodyOutAndKeepsTheRealToken() throws Exception {
    signedIn();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(RpInitiatedLogoutController.CONFIRMATION_TOKEN_ATTRIBUTE, "the-real-one");
    MockHttpServletRequest request = logout("POST");
    request.setSession(session);
    request.setParameter("confirmation_token", "guess");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getForwardedUrl()).isEqualTo("/o/" + ORGANIZATION + "/logout/confirm");
    assertThat(session.isInvalid()).isFalse();
    assertThat(session.getAttribute(RpInitiatedLogoutController.CONFIRMATION_TOKEN_ATTRIBUTE))
        .isEqualTo("the-real-one");
  }

  @Test
  void theRightConfirmationTokenEndsTheSessionAndReturnsToTheRegisteredUri() throws Exception {
    registeredClient();
    signedIn();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(RpInitiatedLogoutController.CONFIRMATION_TOKEN_ATTRIBUTE, "the-real-one");
    MockHttpServletRequest request = logout("POST");
    request.setSession(session);
    request.setParameter("confirmation_token", "the-real-one");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(session.isInvalid()).isTrue();
    assertThat(response.getRedirectedUrl()).isEqualTo(LOGOUT_URI + "?state=s1");
  }

  @Test
  void aRequestWithAHintIsLeftToSpringAuthorizationServer() throws Exception {
    MockHttpServletRequest request = logout("GET");
    request.setParameter("id_token_hint", "a.jwt.value");
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
  }
}
