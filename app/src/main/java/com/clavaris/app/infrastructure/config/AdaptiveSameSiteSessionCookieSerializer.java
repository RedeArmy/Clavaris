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
 *
 * <p><b>Real bug found live, 2026-10-10: one shared cookie name across both tiers.</b>
 * {@code @EnableRedisIndexedHttpSession} is a single, application-wide Spring Session configuration
 * — one {@code SessionRepositoryFilter}, one {@link CookieSerializer} bean, for every request
 * regardless of which {@code SecurityFilterChain} actually handles it. Before this fix, every tier
 * wrote and read the exact same cookie name ({@code "SESSION"}, Spring Session's own default) with
 * the exact same {@code Path=/}, so an operator's own {@code PlatformAccount} dashboard session and
 * a tenant {@code Account}'s own hosted-login session were, to the browser, literally the same
 * cookie slot — whichever tier authenticated more recently overwrote the {@code SecurityContext}
 * already stored in that one shared {@code HttpSession} (same session object, same ID, just a
 * different {@code Authentication} written into it by whichever establisher ran last; {@code
 * SpringSecurityAuthenticatedSessionEstablisher#establishWithAuthorities} and its platform-tier
 * sibling both reuse an existing session via {@code request.getSession(false)} rather than always
 * minting a fresh one). Live-observed: an operator logged into the platform dashboard, who then
 * also logs into a consuming application using the same browser (even a wholly unrelated tenant
 * Account, same email as their own platform login or not — the collision is on the cookie, not the
 * credential), gets silently logged out of the dashboard on the very next click — the shared
 * session now holds the tenant Account's own {@code ROLE_ACCOUNT} authority, not {@code
 * ROLE_PLATFORM_ACCOUNT}, so the dashboard's own chain correctly (from its perspective) treats the
 * request as unauthenticated.
 *
 * <p>Fixed by giving each tier its own cookie <em>name</em> ({@link #PLATFORM_COOKIE_NAME} for
 * {@code /platform/**}, Spring Session's own default {@code "SESSION"} unchanged for every other
 * path) — two genuinely distinct cookies in the browser's own jar from this point on, never
 * colliding regardless of path overlap or login order. Detected the same way {@code
 * PlatformDashboardSecurityConfig}'s own {@code securityMatcher("/platform/**")} scopes that whole
 * chain — a plain prefix check, not a Spring Security construct, since this class runs for every
 * request on every chain alike, before any chain-specific filter does.
 */
// PMD.LongVariable: every name here names exactly what it holds — same "abbreviating would only
// make this harder to read" precedent this codebase's own PlatformScopes/
// OrganizationAuthorizationServerConfig class-level suppressions already establish.
@SuppressWarnings("PMD.LongVariable")
final class AdaptiveSameSiteSessionCookieSerializer implements CookieSerializer {

  // PlatformDashboardSecurityConfig's own securityMatcher — see this class's own Javadoc for why
  // this is checked here as a plain string prefix rather than reused as a Spring construct.
  private static final String PLATFORM_PATH_PREFIX = "/platform/";
  private static final String PLATFORM_COOKIE_NAME = "PLATFORM_SESSION";

  private final DefaultCookieSerializer tenantLaxSerializer = new DefaultCookieSerializer();
  private final DefaultCookieSerializer tenantNoneSerializer = new DefaultCookieSerializer();
  private final DefaultCookieSerializer platformLaxSerializer = new DefaultCookieSerializer();
  private final DefaultCookieSerializer platformNoneSerializer = new DefaultCookieSerializer();

  /* package */ AdaptiveSameSiteSessionCookieSerializer() {
    // Secure is left at DefaultCookieSerializer's own adaptive default on all four (mirrors
    // request.isSecure()), never hardcoded — SameSite and cookie name are the only two axes this
    // class itself decides.
    tenantNoneSerializer.setSameSite("None");
    platformLaxSerializer.setCookieName(PLATFORM_COOKIE_NAME);
    platformNoneSerializer.setCookieName(PLATFORM_COOKIE_NAME);
    platformNoneSerializer.setSameSite("None");
  }

  @Override
  public void writeCookieValue(final CookieValue cookieValue) {
    final HttpServletRequest request = cookieValue.getRequest();
    serializerFor(request).writeCookieValue(cookieValue);
  }

  // SameSite/Secure are response-only attributes — reading back an already-sent cookie value never
  // depends on either, so within one tier this delegates to either inner serializer identically
  // (the Lax one is an arbitrary, equally-correct choice between the two); only the cookie NAME
  // actually matters for a read, which is why tier alone (not tier+secure) selects the serializer
  // here.
  @Override
  public List<String> readCookieValues(final HttpServletRequest request) {
    return serializerFor(request).readCookieValues(request);
  }

  // PMD.OnlyOneReturn: "platform tier" and "tenant tier" are two genuinely distinct outcomes —
  // same "one exit per distinct outcome" rationale every other early-return guard in this
  // codebase already applies.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private DefaultCookieSerializer serializerFor(final HttpServletRequest request) {
    final String path = request.getRequestURI();
    final boolean isPlatform = path.startsWith(PLATFORM_PATH_PREFIX) || "/platform".equals(path);
    final boolean isSecure = request.isSecure();
    if (isPlatform) {
      return isSecure ? platformNoneSerializer : platformLaxSerializer;
    }
    return isSecure ? tenantNoneSerializer : tenantLaxSerializer;
  }
}
