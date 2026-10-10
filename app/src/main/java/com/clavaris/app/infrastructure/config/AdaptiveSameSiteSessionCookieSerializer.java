package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * ADR-0009 §1/§4 / SDE-III review, 2026-09-15, superseded 2026-10-10 (real bug found live, preprod,
 * real browser): the original design here ({@code ModalAwareSessionCookieSerializer}) widened
 * {@code SameSite} to {@code None} only for a request already detected as part of a {@code
 * display=modal} flow (keyed via an {@code HttpSession} attribute). That fixed the embedded login's
 * own first session, but a cookie's {@code SameSite} attribute is fixed at the moment it is written
 * and can never be "upgraded" retroactively by a later request — an Account who already has an
 * ordinary, non-embedded, top-level session (written {@code Lax}, the only kind an ordinary login
 * ever produced) can never have that same session recognized inside a later embedded iframe at all,
 * since a browser excludes a {@code Lax} cookie from every cross-site sub-frame request regardless
 * of that request's own query string. The user-visible result: "Ver mi perfil" (embedded {@code
 * <UserProfile/>} parity) always demanded a second, separate login inside the iframe even for an
 * Account already signed in on the very same browser — the opposite of the single-sign-on
 * experience ADR-0009 exists to provide — and the resulting two independent, simultaneous sessions
 * for one Account left logout in an inconsistent state (a raw Whitelabel Error Page,
 * live-observed).
 *
 * <p><b>The actual fix: stop keying off {@code display=modal} at all.</b> {@code SameSite=None}
 * requires {@code Secure} to be accepted by any modern browser; {@code Secure} is only ever
 * meaningful over a real HTTPS connection in the first place. So the one question that actually
 * matters, for every request alike, ordinary or embedded, is simply whether {@code
 * request.isSecure()} — same adaptive-{@code Secure} precedent {@code DeviceCookie}'s own identical
 * reasoning already establishes for a different cookie. On a real HTTPS deployment (preprod,
 * production — the only place a real third-party iframe embed is ever reachable from anyway, since
 * browsers refuse {@code SameSite=None} without {@code Secure} regardless), every session —
 * embedded or not — is now written {@code SameSite=None; Secure} from the moment it is created, so
 * an ordinary top-level login's own session is recognized by a later embed with no second login
 * required. Over plain HTTP (local dev, this project's own {@code
 * AuthorizationCodeFlowIntegrationTest} and siblings), {@code Secure} can never be true, so this
 * keeps the previous default, {@code SameSite=Lax} — a {@code None} cookie without {@code Secure}
 * would simply be rejected outright by the browser, not "relaxed," so there is nothing a plain-HTTP
 * request could gain from attempting it anyway.
 *
 * <p>A narrower CSRF surface than {@code Lax} would give is not actually lost here in practice:
 * this project already gates every state-changing request behind a real CSRF token (the mechanism
 * {@code Lax} would otherwise be the last line of defense for), the same posture every mainstream
 * hosted-login/embedded-profile provider's own session cookie already takes.
 */
final class AdaptiveSameSiteSessionCookieSerializer implements CookieSerializer {

  private final DefaultCookieSerializer laxSerializer = new DefaultCookieSerializer();
  private final DefaultCookieSerializer noneSerializer = new DefaultCookieSerializer();

  /* package */ AdaptiveSameSiteSessionCookieSerializer() {
    // Deliberately the only difference between the two — Secure is left at DefaultCookieSerializer
    // 's own adaptive default on both (mirrors request.isSecure()), never hardcoded.
    noneSerializer.setSameSite("None");
  }

  // PMD.LawOfDemeter: CookieValue.getRequest() is Spring Session's own standard accessor for
  // exactly this purpose — there is no narrower way to ask "is this request secure" than through
  // it.
  @SuppressWarnings("PMD.LawOfDemeter")
  @Override
  public void writeCookieValue(final CookieValue cookieValue) {
    if (cookieValue.getRequest().isSecure()) {
      noneSerializer.writeCookieValue(cookieValue);
    } else {
      laxSerializer.writeCookieValue(cookieValue);
    }
  }

  // SameSite/Secure are response-only attributes — reading back an already-sent cookie value never
  // depends on either, so this delegates to either inner serializer identically; laxSerializer is
  // an arbitrary, equally-correct choice between the two.
  @Override
  public List<String> readCookieValues(final HttpServletRequest request) {
    return laxSerializer.readCookieValues(request);
  }
}
