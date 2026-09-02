package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Code review finding (2026-09-01): a real cross-Organization cookie-collision bug this class had
 * no unit test coverage of at all — see {@link DeviceCookie}'s own Javadoc for the fix.
 */
class DeviceCookieTest {

  private final UUID organizationA = UUID.randomUUID();
  private final UUID organizationB = UUID.randomUUID();

  @Test
  void readReturnsEmptyWhenNoCookiesArePresent() {
    MockHttpServletRequest request = new MockHttpServletRequest();

    assertThat(DeviceCookie.read(request, organizationA)).isEmpty();
  }

  @Test
  void readReturnsEmptyWhenCookiesArePresentButNoneMatchThisOrganization() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("clavaris_device_" + organizationB, "b-token"));

    assertThat(DeviceCookie.read(request, organizationA)).isEmpty();
  }

  @Test
  void readReturnsThisOrganizationsOwnCookieValue() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("clavaris_device_" + organizationA, "a-token"));

    assertThat(DeviceCookie.read(request, organizationA)).contains("a-token");
  }

  // The actual regression proof: a browser with real Accounts in two different Organizations
  // (ADR-0010 — separate, unrelated account pools, a real scenario) must never have one
  // Organization's device cookie shadow or collide with the other's.
  @Test
  void twoDifferentOrganizationsCookiesCoexistInTheSameBrowserWithoutCollision() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(
        new Cookie("clavaris_device_" + organizationA, "a-token"),
        new Cookie("clavaris_device_" + organizationB, "b-token"));

    assertThat(DeviceCookie.read(request, organizationA)).contains("a-token");
    assertThat(DeviceCookie.read(request, organizationB)).contains("b-token");
  }

  @Test
  void writeSetsACookieNamedAndScopedForThisOrganizationOnly() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    DeviceCookie.write(request, response, organizationA, "fresh-a-token");

    String setCookieHeader = response.getHeader("Set-Cookie");
    assertThat(setCookieHeader)
        .as("the Set-Cookie header must name this Organization's own cookie, not a shared one")
        .startsWith("clavaris_device_" + organizationA + "=fresh-a-token")
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .contains("Path=/");
  }

  // Proves write() for one Organization never clobbers/replaces an already-set cookie for a
  // different one — the server sends two independent Set-Cookie headers, exactly what lets a
  // real browser keep both simultaneously.
  @Test
  void writingForTwoDifferentOrganizationsProducesTwoIndependentSetCookieHeaders() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    DeviceCookie.write(request, response, organizationA, "a-token");
    DeviceCookie.write(request, response, organizationB, "b-token");

    List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
    assertThat(setCookieHeaders).hasSize(2);
    assertThat(setCookieHeaders.get(0)).startsWith("clavaris_device_" + organizationA + "=a-token");
    assertThat(setCookieHeaders.get(1)).startsWith("clavaris_device_" + organizationB + "=b-token");
  }
}
