package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** TD-FUT-026: platform-tier mirror of {@code DeviceCookieTest} — one fixed cookie name. */
class PlatformDeviceCookieTest {

  @Test
  void readReturnsEmptyWhenNoCookiesArePresent() {
    MockHttpServletRequest request = new MockHttpServletRequest();

    assertThat(PlatformDeviceCookie.read(request)).isEmpty();
  }

  @Test
  void readReturnsEmptyWhenCookiesArePresentButNoneMatch() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("some_other_cookie", "value"));

    assertThat(PlatformDeviceCookie.read(request)).isEmpty();
  }

  @Test
  void readReturnsThePlatformDeviceCookiesValue() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("clavaris_platform_device", "a-token"));

    assertThat(PlatformDeviceCookie.read(request)).contains("a-token");
  }

  @Test
  void writeSetsTheCookieWithTheExpectedAttributes() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    PlatformDeviceCookie.write(request, response, "fresh-token");

    String setCookieHeader = response.getHeader("Set-Cookie");
    assertThat(setCookieHeader)
        .startsWith("clavaris_platform_device=fresh-token")
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .contains("Path=/");
  }
}
