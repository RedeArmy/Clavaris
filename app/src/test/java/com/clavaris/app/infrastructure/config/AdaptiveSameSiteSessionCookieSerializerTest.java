package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer.CookieValue;

/**
 * Real bug found live, 2026-10-10 — see {@link AdaptiveSameSiteSessionCookieSerializer}'s own
 * Javadoc for the full rationale this class guards: SameSite is keyed off whether the connection is
 * secure, never off a per-request {@code display=modal} query param, so an ordinary top-level
 * session on a real HTTPS deployment is recognized by a later embedded iframe with no second login.
 */
class AdaptiveSameSiteSessionCookieSerializerTest {

  private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String COOKIE_A = "SESSION_11111111111111111111111111111111";
  private static final String COOKIE_B = "SESSION_22222222222222222222222222222222";

  // Stands in for the client->Organization resolver and the session repository.
  private final Map<String, UUID> clientOrganizations = new HashMap<>();
  private final Set<String> socialLoginSessions = new HashSet<>();
  private final AdaptiveSameSiteSessionCookieSerializer serializer =
      new AdaptiveSameSiteSessionCookieSerializer(
          "/platform/",
          new FlatPathSessionLookup() {
            @Override
            public Optional<UUID> organizationForClient(final String clientId) {
              return Optional.ofNullable(clientOrganizations.get(clientId));
            }

            @Override
            public boolean hasSocialLoginInProgress(final String sessionId) {
              return socialLoginSessions.contains(sessionId);
            }
          });

  private static String encoded(final String sessionId) {
    return Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
  }

  private static String tenantPath(final UUID organizationId, final String rest) {
    return "/o/" + organizationId + rest;
  }

  @Test
  void anInsecureRequestGetsTheLaxFallback() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login"));
    request.setSecure(false);
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax").doesNotContain("Secure");
  }

  // The real regression this class closes: an ordinary, non-embedded, top-level login over HTTPS
  // must ALSO get SameSite=None from the very first response — not just a request already known
  // to be part of a display=modal flow — so a LATER embedded iframe (which can never retroactively
  // widen an already-written Lax cookie) recognizes this same session with no second login.
  @Test
  void aSecureRequestGetsSameSiteNoneRegardlessOfAnyDisplayModalParam() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login"));
    request.setSecure(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=None").contains("Secure");
  }

  @Test
  void aSecureRequestWithDisplayModalStillGetsSameSiteNone() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login"));
    request.setSecure(true);
    request.setParameter("display", "modal");
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=None");
  }

  // SameSite=None without Secure is simply rejected outright by any modern browser — there is
  // nothing a plain-HTTP request could gain from attempting it, display=modal or not.
  @Test
  void anInsecureRequestWithDisplayModalStillGetsOnlyTheLaxFallback() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login"));
    request.setSecure(false);
    request.setParameter("display", "modal");
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
  }

  // Real bug found live, 2026-10-10: see this class's own Javadoc addendum — an operator logged
  // into the platform dashboard who then also logs into a tenant Account in the same browser used
  // to get silently kicked back out of the dashboard, because both tiers wrote the exact same
  // cookie name, so the later login's own session overwrote the earlier one's in the browser's
  // own cookie jar.
  @Test
  void aTenantPathGetsTheOrdinarySessionCookieName() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login"));
    request.setSecure(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).startsWith(COOKIE_A + "=");
  }

  @Test
  void aPlatformPathGetsItsOwnDistinctCookieName() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/platform/dashboard");
    request.setSecure(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie"))
        .startsWith("PLATFORM_SESSION=")
        .contains("SameSite=None")
        .contains("Secure");
  }

  @Test
  void aPlatformLoginPathAlsoGetsThePlatformCookieName() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/platform/login");
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie"))
        .startsWith("PLATFORM_SESSION=")
        .contains("SameSite=Lax");
  }

  @Test
  void readsBackThePlatformCookieNameOnAPlatformPath() {
    String base64Value =
        Base64.getEncoder()
            .encodeToString("a-platform-session-id".getBytes(StandardCharsets.UTF_8));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/platform/dashboard");
    request.setCookies(new Cookie("PLATFORM_SESSION", base64Value));

    assertThat(serializer.readCookieValues(request)).containsExactly("a-platform-session-id");
  }

  // The actual regression: a tenant-tier request must never resolve the platform cookie, even if
  // one happens to be present in the same browser (e.g. an operator also browsing the dashboard).
  @Test
  void aTenantPathNeverReadsThePlatformCookieEvenIfBothArePresent() {
    String tenantValue =
        Base64.getEncoder().encodeToString("tenant-id".getBytes(StandardCharsets.UTF_8));
    String platformValue =
        Base64.getEncoder().encodeToString("platform-id".getBytes(StandardCharsets.UTF_8));
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/account/profile"));
    request.setCookies(
        new Cookie(COOKIE_A, tenantValue), new Cookie("PLATFORM_SESSION", platformValue));

    assertThat(serializer.readCookieValues(request)).containsExactly("tenant-id");
  }

  // Sonar java:S1075: the platform path prefix is a constructor parameter
  // (clavaris.session.platform-path-prefix), not a hardcoded literal — proven here with a
  // different value than every other test in this class uses.
  @Test
  void aCustomPlatformPathPrefixIsHonoredInsteadOfTheHardcodedDefault() {
    AdaptiveSameSiteSessionCookieSerializer customSerializer =
        new AdaptiveSameSiteSessionCookieSerializer("/ops-console/", noLookup());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ops-console/dashboard");
    request.setSecure(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    customSerializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).startsWith("PLATFORM_SESSION=");
  }

  @Test
  void readCookieValuesReadsBackAnOrdinaryCookieHeader() {
    // DefaultCookieSerializer's own default useBase64Encoding=true — the raw cookie value on the
    // wire is Base64, decoded back to the plain session id here, same encoding
    // laxSerializer.writeCookieValue would itself have produced.
    String base64Value =
        Base64.getEncoder().encodeToString("a-session-id".getBytes(StandardCharsets.UTF_8));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
    request.setCookies(new Cookie("SESSION", base64Value));

    assertThat(serializer.readCookieValues(request)).containsExactly("a-session-id");
  }

  private static FlatPathSessionLookup noLookup() {
    return new FlatPathSessionLookup() {
      @Override
      public Optional<UUID> organizationForClient(final String clientId) {
        return Optional.empty();
      }

      @Override
      public boolean hasSocialLoginInProgress(final String sessionId) {
        return false;
      }
    };
  }

  // The bug: both tenants wrote the same cookie, so the second login replaced the first one's
  // session. Two tenants must now write two different cookies.
  @Test
  void eachTenantGetsItsOwnCookieName() {
    MockHttpServletResponse responseA = new MockHttpServletResponse();
    MockHttpServletResponse responseB = new MockHttpServletResponse();

    serializer.writeCookieValue(
        new CookieValue(
            new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login")), responseA, "id-a"));
    serializer.writeCookieValue(
        new CookieValue(
            new MockHttpServletRequest("GET", tenantPath(TENANT_B, "/login")), responseB, "id-b"));

    assertThat(responseA.getHeader("Set-Cookie")).startsWith(COOKIE_A + "=");
    assertThat(responseB.getHeader("Set-Cookie")).startsWith(COOKIE_B + "=");
  }

  @Test
  void aTenantRequestReadsOnlyItsOwnCookie() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_B, "/account/profile"));
    request.setCookies(
        new Cookie(COOKIE_A, encoded("session-of-a")),
        new Cookie(COOKIE_B, encoded("session-of-b")),
        new Cookie("SESSION", encoded("legacy-session")));

    assertThat(serializer.readCookieValues(request)).containsExactly("session-of-b");
  }

  // Sessions from before the per-tenant cookies live under SESSION; a tenant path must not pick
  // them up (the user signs in once more) and must not fail either.
  @Test
  void anOldSharedSessionCookieIsIgnoredOnATenantPath() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/account/profile"));
    request.setCookies(new Cookie("SESSION", encoded("legacy-session")));

    assertThat(serializer.readCookieValues(request)).isEmpty();
  }

  @Test
  void theTenantCookieKeepsTheAdaptiveSameSiteRules() {
    MockHttpServletRequest secure =
        new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login"));
    secure.setSecure(true);
    MockHttpServletResponse secureResponse = new MockHttpServletResponse();
    MockHttpServletResponse plainResponse = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(secure, secureResponse, "id"));
    serializer.writeCookieValue(
        new CookieValue(
            new MockHttpServletRequest("GET", tenantPath(TENANT_A, "/login")),
            plainResponse,
            "id"));

    assertThat(secureResponse.getHeader("Set-Cookie"))
        .startsWith(COOKIE_A + "=")
        .contains("SameSite=None")
        .contains("Secure");
    assertThat(plainResponse.getHeader("Set-Cookie")).contains("SameSite=Lax");
  }

  @Test
  void aPathThatLooksLikeATenantButIsNotAUuidFallsBackToTheSharedCookie() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(
        new CookieValue(new MockHttpServletRequest("GET", "/o/not-a-uuid/login"), response, "id"));

    assertThat(response.getHeader("Set-Cookie")).startsWith("SESSION=");
  }

  @Test
  void theConsentPageReadsTheCookieOfTheOrganizationOwningTheClient() {
    clientOrganizations.put("client-of-b", TENANT_B);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/consent");
    request.setParameter("client_id", "client-of-b");
    request.setCookies(
        new Cookie(COOKIE_A, encoded("session-of-a")),
        new Cookie(COOKIE_B, encoded("session-of-b")));

    assertThat(serializer.readCookieValues(request)).containsExactly("session-of-b");
  }

  @Test
  void theConsentPageForAnUnknownClientReadsNoSessionAtAll() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/consent");
    request.setParameter("client_id", "nobody");
    request.setCookies(
        new Cookie(COOKIE_A, encoded("session-of-a")), new Cookie("SESSION", encoded("legacy")));

    assertThat(serializer.readCookieValues(request)).isEmpty();
  }

  @Test
  void aSocialLoginCallbackReadsTheTenantSessionThatStartedTheLogin() {
    socialLoginSessions.add("session-of-b");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
    request.setCookies(
        new Cookie(COOKIE_A, encoded("session-of-a")),
        new Cookie(COOKIE_B, encoded("session-of-b")),
        new Cookie("SESSION", encoded("legacy")));

    assertThat(serializer.readCookieValues(request)).containsExactly("session-of-b");
  }

  // The platform's own social login uses the same flat endpoints: it must not be handed an
  // authenticated tenant session (it would overwrite it), only the shared cookie as before.
  @Test
  void aSocialLoginEndpointWithNoTenantLoginInProgressKeepsTheSharedCookie() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/google");
    request.setCookies(
        new Cookie(COOKIE_A, encoded("session-of-a")), new Cookie("SESSION", encoded("legacy")));

    assertThat(serializer.readCookieValues(request)).containsExactly("legacy");
  }

  @Test
  void theWriteThatFollowsAFlatReadUsesTheSameCookie() {
    socialLoginSessions.add("session-of-a");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
    request.setCookies(new Cookie(COOKIE_A, encoded("session-of-a")));
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.readCookieValues(request);
    serializer.writeCookieValue(new CookieValue(request, response, "rotated-id"));

    assertThat(response.getHeader("Set-Cookie")).startsWith(COOKIE_A + "=");
  }

  @Test
  void anyOtherFlatPathKeepsUsingTheSharedCookie() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
    request.setCookies(
        new Cookie(COOKIE_A, encoded("session-of-a")), new Cookie("SESSION", encoded("legacy")));

    assertThat(serializer.readCookieValues(request)).containsExactly("legacy");
  }
}
