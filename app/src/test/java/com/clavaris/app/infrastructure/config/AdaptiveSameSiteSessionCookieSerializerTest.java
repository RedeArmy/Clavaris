package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

  private final AdaptiveSameSiteSessionCookieSerializer serializer =
      new AdaptiveSameSiteSessionCookieSerializer("/platform/");

  @Test
  void anInsecureRequestGetsTheLaxFallback() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
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
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
    request.setSecure(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=None").contains("Secure");
  }

  @Test
  void aSecureRequestWithDisplayModalStillGetsSameSiteNone() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
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
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
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
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
    request.setSecure(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).startsWith("SESSION=");
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
        new MockHttpServletRequest("GET", "/o/some-org/account/profile");
    request.setCookies(
        new Cookie("SESSION", tenantValue), new Cookie("PLATFORM_SESSION", platformValue));

    assertThat(serializer.readCookieValues(request)).containsExactly("tenant-id");
  }

  // Sonar java:S1075: the platform path prefix is a constructor parameter
  // (clavaris.session.platform-path-prefix), not a hardcoded literal — proven here with a
  // different value than every other test in this class uses.
  @Test
  void aCustomPlatformPathPrefixIsHonoredInsteadOfTheHardcodedDefault() {
    AdaptiveSameSiteSessionCookieSerializer customSerializer =
        new AdaptiveSameSiteSessionCookieSerializer("/ops-console/");
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
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("SESSION", base64Value));

    assertThat(serializer.readCookieValues(request)).containsExactly("a-session-id");
  }
}
