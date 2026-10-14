package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.util.UrlPathHelper;

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
 *
 * <p><b>Second real bug, 2026-10-14: every tenant shared {@code SESSION}.</b> Separating the
 * platform from the tenants left the tenants themselves on one cookie: signing in to a second
 * Organization in the same browser reused the first one's {@code HttpSession} and overwrote its
 * {@code SecurityContext}, signing the first one out. Each Organization now has its own cookie,
 * {@code SESSION_<organizationId>} (see {@link TenantSessionCookies}), picked from the request:
 *
 * <ul>
 *   <li>{@code /o/{organizationId}/...}: that Organization's cookie. This is every tenant page and
 *       endpoint, and also what a verified custom domain is rewritten to before the session filter
 *       runs.
 *   <li>{@code /oauth2/consent}: flat, because Spring Authorization Server fixes that URL, so the
 *       Organization comes from its {@code client_id} (the page already resolves it the same way).
 *       A {@code client_id} that belongs to no Organization reads no session at all, never another
 *       Organization's.
 *   <li>{@code /oauth2/authorization/**} and {@code /login/oauth2/code/**} (the social-login round
 *       trip, flat for the same reason): the tier that started the login travels with it, as the
 *       {@code clavaris_tenant} parameter on the first hop and as a prefix of the OAuth {@code
 *       state} on the provider's callback, so two logins pending in two Organizations each read
 *       their own cookie, and the platform's own social login reads {@code PLATFORM_SESSION}. A
 *       request without a well-formed tag keeps {@code SESSION}.
 *   <li>Any other flat path: {@code SESSION}, exactly as before.
 * </ul>
 *
 * Sessions created before this change live under {@code SESSION} and are no longer read on tenant
 * paths: each user signs in once more, and the old cookie is ignored from then on.
 */
// PMD.LongVariable: every name here names exactly what it holds — same "abbreviating would only
// make this harder to read" precedent this codebase's own PlatformScopes/
// OrganizationAuthorizationServerConfig class-level suppressions already establish.
// java:S1075: the consent and social-login paths are fixed by Spring Authorization Server and
// Spring Security, not deployment settings (the platform prefix, which is, stays a parameter).
// PMD.OnlyOneReturn: one exit per distinct outcome, as everywhere else in this codebase.
@SuppressWarnings({"PMD.LongVariable", "java:S1075", "PMD.OnlyOneReturn"})
final class AdaptiveSameSiteSessionCookieSerializer implements CookieSerializer {

  private static final String PLATFORM_COOKIE_NAME = "PLATFORM_SESSION";
  private static final String CONSENT_PATH = "/oauth2/consent";
  private static final String CLIENT_ID_PARAM = "client_id";
  private static final String SOCIAL_START_PREFIX = "/oauth2/authorization/";
  private static final String SOCIAL_CALLBACK_PREFIX = "/login/oauth2/code/";
  private static final String STATE_PARAM = "state";

  // The cookie a flat request resolved to, kept on the request so the write that follows the read
  // does not look it up (and scan sessions) a second time.
  private static final String RESOLVED_NAME_ATTRIBUTE =
      AdaptiveSameSiteSessionCookieSerializer.class.getName() + ".RESOLVED_NAME";

  // Sonar java:S1075 ("URIs should not be hardcoded"): PlatformDashboardSecurityConfig's own
  // securityMatcher("/platform/**") is itself a plain literal, not an externalized property — but
  // this class's own copy of it is a constructor parameter (clavaris.session.platform-path-prefix,
  // defaulted to the same value) so a deployment could move the platform tier to a different mount
  // path without a source change, same pattern this codebase's own
  // CachingAccountAuthenticationPolicyProvider/RedisIdempotencyKeyStore constructors already use.
  private final String platformPathPrefix;
  private final FlatPathSessionLookup flatPathLookup;

  /* package */ AdaptiveSameSiteSessionCookieSerializer(
      @Value("${clavaris.session.platform-path-prefix:/platform/}") final String platformPathPrefix,
      final FlatPathSessionLookup flatPathLookup) {
    this.platformPathPrefix = platformPathPrefix;
    this.flatPathLookup = flatPathLookup;
  }

  @Override
  public void writeCookieValue(final CookieValue cookieValue) {
    final HttpServletRequest request = cookieValue.getRequest();
    serializerFor(request, cookieNameFor(request).orElse(TenantSessionCookies.LEGACY_NAME))
        .writeCookieValue(cookieValue);
  }

  // SameSite/Secure are response-only attributes — reading back an already-sent cookie value never
  // depends on either, so only the cookie NAME matters for a read. A request that resolves to no
  // cookie at all (a consent page for a client of no Organization) reads no session.
  @Override
  public List<String> readCookieValues(final HttpServletRequest request) {
    return cookieNameFor(request)
        .map(name -> serializerFor(request, name).readCookieValues(request))
        .orElse(List.of());
  }

  private Optional<String> cookieNameFor(final HttpServletRequest request) {
    final String path = pathWithinContext(request);
    if (isPlatform(path)) {
      return Optional.of(PLATFORM_COOKIE_NAME);
    }
    final Optional<String> tenant = TenantSessionCookies.nameForPath(path);
    if (tenant.isPresent()) {
      return tenant;
    }
    if (CONSENT_PATH.equals(path)) {
      return resolvedOnce(request, () -> consentCookie(request));
    }
    if (path.startsWith(SOCIAL_START_PREFIX)) {
      return Optional.of(
          socialLoginCookie(
              request.getParameter(SocialLoginRedirectController.TENANT_TAG_PARAMETER)));
    }
    if (path.startsWith(SOCIAL_CALLBACK_PREFIX)) {
      return Optional.of(
          socialLoginCookie(
              TenantSessionCookies.tagOfState(request.getParameter(STATE_PARAM)).orElse(null)));
    }
    return Optional.of(TenantSessionCookies.LEGACY_NAME);
  }

  private Optional<String> resolvedOnce(
      final HttpServletRequest request,
      final java.util.function.Supplier<Optional<String>> resolve) {
    final Object cached = request.getAttribute(RESOLVED_NAME_ATTRIBUTE);
    if (cached instanceof String name) {
      return Optional.of(name);
    }
    final Optional<String> resolved = resolve.get();
    resolved.ifPresent(name -> request.setAttribute(RESOLVED_NAME_ATTRIBUTE, name));
    return resolved;
  }

  private Optional<String> consentCookie(final HttpServletRequest request) {
    final String clientId = request.getParameter(CLIENT_ID_PARAM);
    if (clientId == null || clientId.isBlank()) {
      return Optional.empty();
    }
    return flatPathLookup.organizationForClient(clientId).map(TenantSessionCookies::nameFor);
  }

  // The tag names the tier that started this social login (see SocialLoginRedirectController), so
  // two logins pending at once in two Organizations each read their own session. A request with no
  // tag, or one that is not a well-formed tag, keeps the legacy cookie: a forged tag can only pick
  // another cookie of the same browser, and Spring Security then fails to find the saved
  // authorization request for that state in it.
  private static String socialLoginCookie(final String tag) {
    return TenantSessionCookies.nameForTag(tag).orElse(TenantSessionCookies.LEGACY_NAME);
  }

  // A cheap, throwaway serializer per call rather than a cache keyed by cookie name: tenant cookie
  // names come from the browser and from URLs, so a cache would grow with whatever a client sends.
  // Secure is left at DefaultCookieSerializer's own adaptive default (mirrors request.isSecure()),
  // never hardcoded — SameSite and the cookie name are the only two axes this class decides.
  private static DefaultCookieSerializer serializerFor(
      final HttpServletRequest request, final String cookieName) {
    final DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(cookieName);
    if (request.isSecure()) {
      serializer.setSameSite("None");
    }
    return serializer;
  }

  private boolean isPlatform(final String path) {
    final String withoutTrailingSlash =
        platformPathPrefix.substring(0, platformPathPrefix.length() - 1);
    return path.startsWith(platformPathPrefix) || withoutTrailingSlash.equals(path);
  }

  // Decoded and normalized the way Spring MVC and the security chains see it: a percent-encoded
  // character in /o/{id}/... reaches the same endpoint as the plain path, so the cookie must be
  // chosen from the same path or that request would use the legacy cookie.
  private static String pathWithinContext(final HttpServletRequest request) {
    return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
  }
}
