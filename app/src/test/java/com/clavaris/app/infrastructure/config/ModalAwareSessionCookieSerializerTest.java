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
 * SDE-III review, 2026-09-15 — see {@link ModalAwareSessionCookieSerializer}'s own Javadoc for the
 * full rationale this class guards.
 */
class ModalAwareSessionCookieSerializerTest {

  private final ModalAwareSessionCookieSerializer serializer =
      new ModalAwareSessionCookieSerializer();

  @Test
  void anOrdinaryRequestGetsTheDefaultLaxSameSite() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
  }

  // See ModalAwareSessionCookieSerializer's own Javadoc for the real bug this closes: without
  // this, the embedded/iframe login flow's own session cookie never survives past the first
  // response, since a Lax cookie is never sent on a cross-site iframe's own requests at all.
  @Test
  void aDisplayModalRequestGetsSameSiteNoneInstead() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
    request.setParameter("display", "modal");
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=None");
  }

  // The real regression a bare per-request query-param check would still have: the POST login
  // submit and the redirect back to /oauth2/authorize never carry display=modal themselves, but
  // the session cookie written on THOSE responses still needs SameSite=None for the whole
  // embedded flow to actually work end to end, not just its very first response.
  @Test
  void aLaterRequestInTheSameModalFlowStillGetsSameSiteNoneWithoutTheQueryParam() {
    MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/o/some-org/login");
    firstRequest.setParameter("display", "modal");
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    serializer.writeCookieValue(new CookieValue(firstRequest, firstResponse, "a-session-id"));

    MockHttpServletRequest secondRequest = new MockHttpServletRequest("POST", "/o/some-org/login");
    secondRequest.setSession(firstRequest.getSession());
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();

    serializer.writeCookieValue(
        new CookieValue(secondRequest, secondResponse, "a-rotated-session-id"));

    assertThat(secondResponse.getHeader("Set-Cookie")).contains("SameSite=None");
  }

  @Test
  void aFreshUnrelatedRequestOnTheSameHttpSessionButNeverHavingSeenDisplayModalStaysLax() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/login");
    MockHttpServletResponse response = new MockHttpServletResponse();

    serializer.writeCookieValue(new CookieValue(request, response, "a-session-id"));

    assertThat(response.getHeader("Set-Cookie"))
        .contains("SameSite=Lax")
        .doesNotContain("SameSite=None");
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
