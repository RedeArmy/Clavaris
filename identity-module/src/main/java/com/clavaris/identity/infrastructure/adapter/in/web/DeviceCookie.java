package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
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
 * when nothing overrides it. {@code Path=/}, not scoped to {@code /o/{organizationId}}: the
 * social-login callback this cookie must also be readable from ({@code SocialLoginConfig}'s own
 * {@code /login/oauth2/code/**}) lives outside that prefix entirely — a narrower path would
 * silently never reach it.
 *
 * <p><b>Code review finding (2026-09-01), fixed same day:</b> the cookie <i>name</i> is namespaced
 * per {@code organizationId} — a single global name, combined with {@code Path=/}, meant a browser
 * used to log into two different Organizations (ADR-0010: a real, unrelated-account-pool scenario,
 * not a hypothetical) had its one cookie perpetually overwritten by whichever Organization
 * authenticated last, so alternating between them triggered a "new device" email on every single
 * login. Each Organization now gets its own independent cookie in the same browser, coexisting
 * under the shared {@code Path=/} without colliding — the actual cross-Organization isolation this
 * class's own Javadoc already relied on server-side ({@code deviceTokenHash} is only ever looked up
 * scoped to one {@code accountId}) now also holds for the cookie itself.
 */
public final class DeviceCookie {

  @SuppressWarnings("PMD.LongVariable") // matches the cookie name it prefixes, not arbitrarily
  // long — same precedent as other Clavaris-prefixed constants elsewhere in this codebase.
  private static final String COOKIE_NAME_PREFIX = "clavaris_device_";

  // Long-lived on purpose: this is "remember this browser," not a session-lifetime artifact.
  // 365 days stays safely under Chrome's own 400-day hard cap on any cookie's Max-Age.
  private static final Duration MAX_AGE = Duration.ofDays(365);

  private DeviceCookie() {
    // Static utility — not instantiable.
  }

  /**
   * Empty when the request carries no cookie for this exact {@code organizationId} — never thrown
   * for that case, and never matches a different Organization's own cookie in the same browser.
   */
  // "No cookies at all on this request" vs. "cookies present but none match this Organization" are
  // two distinct, equally-valid exits — same "each outcome needs its own exit" rationale as e.g.
  // RegisterAccountController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  public static Optional<String> read(final HttpServletRequest request, final UUID organizationId) {
    final Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    final String cookieName = cookieName(organizationId);
    return Arrays.stream(cookies)
        .filter(cookie -> cookieName.equals(cookie.getName()))
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
      final UUID organizationId,
      final String rawDeviceToken) {
    final ResponseCookie cookie =
        ResponseCookie.from(cookieName(organizationId), rawDeviceToken)
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(MAX_AGE)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  // UUID's own canonical string form (hex digits + hyphens) is already a valid RFC 6265
  // cookie-name token — no further sanitizing/encoding needed.
  private static String cookieName(final UUID organizationId) {
    return COOKIE_NAME_PREFIX + organizationId;
  }
}
