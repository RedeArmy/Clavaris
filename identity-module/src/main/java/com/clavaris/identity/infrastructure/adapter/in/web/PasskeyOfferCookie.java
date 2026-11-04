package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * ADR-0039: remembers, per browser and Organization, that the person chose "don't ask again" on the
 * passkey offer. It is a preference, not a credential: it grants nothing, skips no check, and
 * forging or deleting it only hides or shows a page. That is why this is a plain cookie and not the
 * signed, device-bound token a "remembered browser" that skipped the code would have needed.
 */
/* package */ final class PasskeyOfferCookie {

  private static final String NAME_PREFIX = "clavaris_passkey_offer_";
  private static final Duration MAX_AGE = Duration.ofDays(180);

  private PasskeyOfferCookie() {
    // Static utility.
  }

  /* package */ static boolean isDismissed(
      final HttpServletRequest request, final UUID organizationId) {
    final Cookie[] cookies = request.getCookies();
    final String name = NAME_PREFIX + organizationId;
    return cookies != null && Arrays.stream(cookies).anyMatch(c -> name.equals(c.getName()));
  }

  /* package */ static void dismiss(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId) {
    final ResponseCookie cookie =
        ResponseCookie.from(NAME_PREFIX + organizationId, "1")
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(MAX_AGE)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
