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
 * TD-SEC-033: the opaque, {@code HttpOnly} cookie {@code RecordAccountLoginDeviceService} keys its
 * device recognition by — see {@code KnownDevice}'s own Javadoc for why this replaced the original
 * {@code User-Agent}-only fingerprint. Public (not package-private, unlike most of this package's
 * own helpers): both tenant login paths need it — {@link LoginController} (this module) and {@code
 * SocialLoginAuthenticationSuccessHandler} (the {@code app} module, which already depends on this
 * one) — and neither should duplicate cookie-attribute logic that must agree between them.
 *
 * <p>{@code Secure} mirrors {@code request.isSecure()} rather than being hardcoded — same adaptive
 * default Spring Session's own {@code DefaultCookieSerializer} applies to the session cookie itself
 * when nothing overrides it, confirmed by inspecting that class: local HTTP dev keeps working,
 * production HTTPS gets the flag automatically, with no environment-specific config needed here.
 * {@code Path=/}, not scoped to {@code /o/{organizationId}}: the social-login callback this cookie
 * must also be readable from ({@code SocialLoginConfig}'s own {@code /login/oauth2/code/**}) lives
 * outside that prefix entirely — a narrower path would silently never reach it. This doesn't weaken
 * cross-Organization isolation: the token's own hash is only ever looked up scoped to one specific
 * {@code accountId} server-side, and no two Organizations' account pools ever share a row
 * (ADR-0010) for a stray cookie value to spuriously match against.
 */
public final class DeviceCookie {

  /* package */ static final String COOKIE_NAME = "clavaris_device";

  // Long-lived on purpose — this is "remember this browser," not a session-lifetime artifact;
  // 365 days stays safely under Chrome's own 400-day hard cap on any cookie's Max-Age.
  private static final Duration MAX_AGE = Duration.ofDays(365);

  private DeviceCookie() {
    // Static utility — not instantiable.
  }

  /** Empty when the request carries no cookie of this name at all — never thrown for that case. */
  // "No cookies at all on this request" vs. "cookies present but none match" are two distinct,
  // equally-valid exits — same "each outcome needs its own exit" rationale as e.g.
  // RegisterAccountController's own identical suppression.
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
   * @param rawDeviceToken the same raw (never-hashed) value {@code RecordAccountLoginDeviceService}
   *     just minted and is persisting the hash of — this is the one place the raw value is ever
   *     handed to anything outside that service, and only to write it into the response, never
   *     logged or stored anywhere itself.
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
