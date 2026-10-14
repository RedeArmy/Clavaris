package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.app.infrastructure.adapter.out.security.RequestScopedConsentService;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The notes the filter leaves in the session when it asks for a fresh login must never let a later
 * prompt=login through (they expire and apply only to the request they were left for), and must
 * never be lost to another authorize request while their own request waits for the login. Nothing
 * the filter does destroys the End-User's authentication or consent. A controllable clock makes the
 * expiry exact.
 */
class OidcAuthorizationRequestParamsFilterTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final String CLIENT = "client_abc";
  private static final String REDIRECT = "https://app.example.com/cb";
  private static final Instant T0 = Instant.parse("2026-10-13T12:00:00Z");

  private final OAuthClientRepository clients = mock(OAuthClientRepository.class);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private OidcAuthorizationRequestParamsFilter filterAt(final Instant now) {
    OAuthClient client = mock(OAuthClient.class);
    when(client.active()).thenReturn(true);
    when(client.organizationId()).thenReturn(ORGANIZATION);
    when(client.redirectUris()).thenReturn(List.of(REDIRECT));
    when(client.id()).thenReturn(UUID.randomUUID());
    when(clients.findByClientId(CLIENT)).thenReturn(Optional.of(client));
    return new OidcAuthorizationRequestParamsFilter(clients, Clock.fixed(now, ZoneOffset.UTC));
  }

  private static MockHttpServletRequest authorize(final MockHttpSession session, final String q) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + ORGANIZATION + "/oauth2/authorize");
    request.setSession(session);
    request.setQueryString(q);
    for (String pair : q.split("&")) {
      String[] kv = pair.split("=", 2);
      request.setParameter(kv[0], kv[1]);
    }
    return request;
  }

  private static ReauthenticationMarker.Notes markerFor(final String q, final Instant at) {
    return ReauthenticationMarker.create(authorize(new MockHttpSession(), q), at);
  }

  private static void signedInSince(final Instant factorAt) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.randomUUID().toString(),
                null,
                List.of(
                    FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                        .issuedAt(factorAt)
                        .build(),
                    new SimpleGrantedAuthority("ROLE_ACCOUNT"))));
  }

  private static final String QUERY =
      "client_id=" + CLIENT + "&redirect_uri=" + REDIRECT + "&prompt=login&state=s1";

  private boolean passedThrough(final MockHttpSession session, final Instant now, final String q)
      throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filterAt(now).doFilter(authorize(session, q), response, chain);
    return chain.getRequest() != null;
  }

  // The container and Spring MVC decode the path, so /o/%38.../oauth2/authorize reaches the same
  // endpoint as /o/8.../oauth2/authorize: the filter must treat both alike, or an encoded character
  // would skip prompt and max_age.
  @Test
  void aPercentEncodedPathIsFilteredLikeThePlainOne() throws Exception {
    String id = ORGANIZATION.toString();
    String encodedId = "%" + String.format("%02X", (int) id.charAt(0)) + id.substring(1);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + encodedId + "/oauth2/authorize");
    request.setSession(new MockHttpSession());
    request.setParameter("client_id", CLIENT);
    request.setParameter("redirect_uri", REDIRECT);
    request.setParameter("prompt", "none");
    request.setParameter("state", "s1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filterAt(T0).doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getRedirectedUrl()).contains("error=login_required");
  }

  @Test
  void aFreshNoteForTheSameRequestLetsTheResumedRequestPassAfterTheNewLogin() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE, markerFor(QUERY, T0));
    signedInSince(T0.plusSeconds(30));

    assertThat(passedThrough(session, T0.plusSeconds(60), QUERY)).isTrue();
    assertThat(session.getAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE)).isNull();
  }

  @Test
  void anExpiredNoteNoLongerLetsAPromptLoginThrough() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE, markerFor(QUERY, T0));
    // A normal login long after the note, then the same request again.
    Instant later = T0.plus(ReauthenticationMarker.TTL).plus(Duration.ofMinutes(1));
    signedInSince(later.minusSeconds(10));

    assertThat(passedThrough(session, later, QUERY)).isFalse();
  }

  @Test
  void aNoteLeftForAnotherRequestNeverLetsThisPromptLoginThrough() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        ReauthenticationMarker.SESSION_ATTRIBUTE, markerFor(QUERY.replace("s1", "abandoned"), T0));
    signedInSince(T0.plusSeconds(30));

    assertThat(passedThrough(session, T0.plusSeconds(60), QUERY)).isFalse();
  }

  // The note of a request that is waiting for its login must survive another authorize request
  // (a second tab, a retry): wiping it made the resumed request ask for a second login.
  @Test
  void anotherAuthorizeRequestLeavesTheNoteOfAWaitingRequestAlone() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE, markerFor(QUERY, T0));
    signedInSince(T0.plusSeconds(30));

    assertThat(
            passedThrough(
                session, T0.plusSeconds(60), "client_id=" + CLIENT + "&redirect_uri=" + REDIRECT))
        .isTrue();
    assertThat(session.getAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE)).isNotNull();
    assertThat(passedThrough(session, T0.plusSeconds(61), QUERY)).isTrue();
    assertThat(session.getAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE)).isNull();
  }

  // A prompt=login, then another request, then back to the first after the login: the one login is
  // enough, and the other request is not disturbed.
  @Test
  void requestAThenRequestBThenTheResumedRequestAStillNeedsOnlyOneLogin() throws Exception {
    MockHttpSession session = new MockHttpSession();
    String other = "client_id=" + CLIENT + "&redirect_uri=" + REDIRECT + "&prompt=login&state=b";
    // Nobody is signed in: A asks for its login and leaves its note, then B does the same.
    assertThat(passedThrough(session, T0, QUERY)).isTrue();
    assertThat(passedThrough(session, T0.plusSeconds(5), other)).isTrue();

    signedInSince(T0.plusSeconds(30));

    assertThat(passedThrough(session, T0.plusSeconds(60), QUERY)).isTrue();
    assertThat(passedThrough(session, T0.plusSeconds(61), other)).isTrue();
    assertThat(session.getAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE)).isNull();
  }

  @Test
  void theNextAuthorizeRequestForgetsTheNotesThatHaveExpired() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE, markerFor(QUERY, T0));
    Instant later = T0.plus(ReauthenticationMarker.TTL).plusSeconds(1);

    passedThrough(session, later, "client_id=" + CLIENT + "&redirect_uri=" + REDIRECT);

    assertThat(session.getAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE)).isNull();
  }

  // A request from another site must never log the visitor out: asking for a fresh login only
  // shows the login page, the current authentication stays until a new login replaces it.
  @Test
  void promptLoginNeverDropsTheCurrentAuthentication() throws Exception {
    MockHttpSession session = new MockHttpSession();
    signedInSince(T0.minus(Duration.ofHours(1)));
    MockHttpServletRequest request = authorize(session, QUERY);
    request.addHeader("Sec-Fetch-Dest", "iframe");
    request.addHeader("Sec-Fetch-Site", "cross-site");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filterAt(T0).doFilter(request, response, new MockFilterChain());

    assertThat(response.getRedirectedUrl()).contains("/login");
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .as("the visitor is still signed in")
        .isNotNull();
  }

  // prompt=consent only marks the request: the stored consent is ignored for it alone and nothing
  // is deleted (RequestScopedConsentService does the ignoring).
  @Test
  void promptConsentMarksTheRequestAndDeletesNothing() throws Exception {
    MockHttpSession session = new MockHttpSession();
    signedInSince(T0);
    MockHttpServletRequest request =
        authorize(session, "client_id=" + CLIENT + "&redirect_uri=" + REDIRECT + "&prompt=consent");
    MockFilterChain chain = new MockFilterChain();

    filterAt(T0.plusSeconds(1)).doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(request.getAttribute(RequestScopedConsentService.IGNORE_STORED_CONSENT_ATTRIBUTE))
        .isEqualTo(Boolean.TRUE);
  }

  // The saved request that resumes after the login carries Spring Security's own "continue"
  // parameter; it is still the same request.
  @Test
  void theResumedRequestWithSpringSecuritiesContinueParameterStillCounts() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE, markerFor(QUERY, T0));
    signedInSince(T0.plusSeconds(30));

    assertThat(passedThrough(session, T0.plusSeconds(60), QUERY + "&continue=")).isTrue();
  }

  // The error redirect goes to the URI the client registered, built with the new parameters
  // appended; a URI that merely resembles it (another path, host case, userinfo, fragment, scheme
  // or a longer host) is not registered, so nothing redirects to it.
  @Test
  void anErrorRedirectGoesToTheRegisteredUriAndNeverToALookAlike() throws Exception {
    MockHttpServletResponse registered = new MockHttpServletResponse();
    filterAt(T0)
        .doFilter(
            authorize(
                new MockHttpSession(),
                "client_id=" + CLIENT + "&redirect_uri=" + REDIRECT + "&prompt=none&state=s1"),
            registered,
            new MockFilterChain());
    assertThat(registered.getRedirectedUrl())
        .isEqualTo(REDIRECT + "?error=login_required&state=s1");

    for (String lookAlike :
        List.of(
            REDIRECT + "/",
            "https://APP.example.com/cb",
            "https://user@app.example.com/cb",
            REDIRECT + "#fragment",
            "http://app.example.com/cb",
            "https://app.example.com.evil.example/cb",
            "https://evil.example/cb")) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();
      filterAt(T0)
          .doFilter(
              authorize(
                  new MockHttpSession(),
                  "client_id=" + CLIENT + "&redirect_uri=" + lookAlike + "&prompt=none&state=s1"),
              response,
              chain);
      assertThat(response.getRedirectedUrl()).as(lookAlike).isNull();
    }
  }
}
