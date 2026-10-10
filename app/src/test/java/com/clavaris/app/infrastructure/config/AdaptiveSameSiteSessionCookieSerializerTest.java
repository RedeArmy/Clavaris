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
      new AdaptiveSameSiteSessionCookieSerializer();

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
