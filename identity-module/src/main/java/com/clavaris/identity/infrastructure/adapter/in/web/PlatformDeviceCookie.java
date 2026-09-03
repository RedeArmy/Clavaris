package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * TD-FUT-026 (closed 2026-09-02): the platform-tier mirror of {@link DeviceCookie} — {@code
 * RecordPlatformAccountLoginDeviceService}'s own device-recognition cookie. One fixed cookie name,
 * unlike {@link DeviceCookie}'s own per-{@code organizationId} namespacing: the platform tier has
 * exactly one issuer and no Organization to collide across (ADR-0012) — the cross-Organization
 * overwrite bug {@link DeviceCookie}'s own Javadoc documents fixing structurally cannot occur here.
 */
public final class PlatformDeviceCookie {

  private static final String COOKIE_NAME = "clavaris_platform_device";

  // Same 365-day rationale as DeviceCookie's own identical constant.
  private static final Duration MAX_AGE = Duration.ofDays(365);

  private PlatformDeviceCookie() {
    // Static utility — not instantiable.
  }

  // "No cookies at all" vs. "no matching cookie" are two independent, equally valid exits — same
  // rationale as DeviceCookie#read's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  public static Optional<String> read(final HttpServletRequest request) {
    final Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
        .map(Cookie::getValue)
        .findFirst();
  }

  /**
   * @param rawDeviceToken the same raw (never-hashed) value {@code
   *     RecordPlatformAccountLoginDeviceService} just minted and is persisting the hash of — same
   *     "only ever handed to the response, never logged or stored as-is" principle {@link
   *     DeviceCookie#write} already establishes.
   */
  public static void write(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final String rawDeviceToken) {
    final ResponseCookie cookie =
        ResponseCookie.from(COOKIE_NAME, rawDeviceToken)
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(MAX_AGE)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
