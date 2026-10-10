package com.clavaris.app.infrastructure.config;

import com.clavaris.app.infrastructure.adapter.out.bridge.EmbeddingEligibilityChecker;
import com.clavaris.app.infrastructure.adapter.out.bridge.RedirectUriOriginResolver;
import com.clavaris.identity.infrastructure.adapter.in.web.ConsumerBrandNameInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.header.HeaderWriter;

/**
 * TD-SEC-009: {@code security-architecture.md} §5 named CSP as the one header Spring Security's own
 * zero-config defaults never send — {@code X-Content-Type-Options}, {@code X-Frame-Options}, and a
 * conditional HSTS header all come free; CSP does not, and without it an XSS in the
 * Thymeleaf-rendered login/register/consent surface has no backstop.
 *
 * <p>Content-type-gated, not path-listed: only ever sets the header on an {@code Accept}/response
 * that's actually {@code text/html} — every hosted-UI response this project renders (login,
 * register, forgot/reset-password, verify-email, the platform dashboard) sets its content type
 * before this writer's callback fires (Spring Security's {@code HeaderWriterFilter} defers header
 * writing to just before the response actually commits, which for a normal small Thymeleaf/servlet
 * response is well after {@code setContentType} has already run) — confirmed live against a real
 * running instance for the login/register/platform-login/Actuator/{@code /oauth2/token} paths
 * (strict policy present on every HTML page, absent on every JSON/Actuator response), not assumed
 * from the mechanism's description. This means the header is never set on Actuator/JSON/redirect
 * responses sharing the same chains, without needing to enumerate every hosted-UI path by hand and
 * keep that list in sync as pages are added.
 *
 * <p><b>TD-SEC-011 (2026-09-06): SAS's own unbranded {@code DefaultConsentPage} can no longer
 * render at all.</b> {@code OrganizationAuthorizationServerConfig} now unconditionally configures a
 * {@code consentPage(...)}, which switches {@code hasConsentUri()} permanently true for every
 * request on this chain — SAS's own inline Bootstrap-CDN/{@code unsafe-inline} page (the reason a
 * dedicated, weaker {@code CONSENT_PAGE_POLICY} used to exist here) is now structurally
 * unreachable, not just deprecated. The project-owned replacement ({@code ConsentController},
 * {@code identity/consent.html}) needs no script at all and only the same one conditional inline
 * {@code <style>} block {@code identity/login.html} already uses for {@code primaryColor} — it gets
 * the plain {@link #STRICT_POLICY}, the same as every other template this project owns other than
 * the login page (grep confirms zero {@code <script>} anywhere under {@code
 * identity-module}'s/{@code organization-module}'s own {@code resources/templates} besides {@code
 * login.html}'s own two same-origin scripts). <b>Investigating this originally surfaced
 * TD-SEC-026</b> — {@code requireAuthorizationConsent} was never set to {@code true} anywhere, so
 * no consent page of any kind structurally ever rendered for any client. That's long closed
 * (ADR-0017, TD-SEC-026): consent is a real, per-client {@code OAuthClient} attribute, defaulting
 * to required, and this branch is live-verified against an actually-rendered consent screen (see
 * {@code AuthorizationCodeFlowIntegrationTest}'s own consent-required test), not just unit-tested
 * path-matching.
 *
 * <p><b>Code review finding (2026-09-01), the login page's own real script:</b> {@code
 * identity/login.html} ({@code LoginController}'s {@code /o/{organizationId}/login}) now loads one
 * same-origin, external script — {@code login-submit-guard.js}, a client-side, cross-tab mutex
 * against the duplicate-notification race documented on {@code KnownDevice}'s own Javadoc ("two
 * concurrent logins... producing two rows and two notifications for what's really one physical
 * device"). This is the one category of fix for that finding that doesn't reopen TD-SEC-033 — it
 * runs entirely inside the victim's own browser, coordinating via {@code localStorage}, which a
 * different origin (an attacker's own browser) structurally cannot read or write — so it earns its
 * own policy, scoped no wider than {@code script-src 'self'}: same-origin only, and deliberately
 * without {@code 'unsafe-inline'}, unlike the consent page above (that page's inline script is
 * SAS's own code this project doesn't control; this one is project-owned and has no reason to be
 * inline).
 *
 * <p>ADR-0009 §1/§4: on the login page, {@code display=modal} + a {@code clientId} query param
 * resolved as embedding-eligible by {@link EmbeddingEligibilityChecker} relaxes {@code
 * frame-ancestors} from {@code 'none'} to that one client's own registered origin, for that one
 * request only — every other request on every other path keeps {@code 'none'}, unconditionally.
 * Deliberately does <b>not</b> also disable Spring Security's own zero-config {@code
 * X-Frame-Options: DENY} default: every evergreen browser gives CSP {@code frame-ancestors}
 * precedence over the legacy header when both are present (the CSP Level 2 spec's own documented
 * behavior), so the relaxation above already works in practice; disabling the chain-wide default
 * would have also stripped {@code X-Frame-Options} from this same chain's non-HTML JSON responses
 * ({@code /oauth2/token}, {@code /userinfo}) — a real regression to an existing protection, for a
 * benefit that only matters to browsers old enough to not understand CSP framing directives at all.
 * Constructed with an {@link EmbeddingEligibilityChecker} at every {@code SecurityFilterChain}
 * builder site (not just {@code OrganizationAuthorizationServerConfig}'s own) for a uniform
 * constructor shape — the other three call sites simply never reach a request whose path matches
 * {@link #LOGIN_PAGE_PATH}/{@link #CONSENT_PAGE_PATH}, so the checker there is never actually
 * invoked.
 *
 * <p><b>TD-SEC-011: the consent page's own relaxation reads {@code client_id}</b> (OAuth2's own
 * snake_case parameter — confirmed by reading {@code
 * OAuth2AuthorizationEndpointFilter#sendAuthorizationConsent} directly), never this project's own
 * camelCase {@code clientId} used on the login page — a real, previously-untested parameter-name
 * mismatch this pass fixes, not something introduced by it.
 *
 * <p><b>TD-SEC-050 (closed): {@code display=modal} on the consent page.</b> SAS's own internal
 * redirect from {@code /o/{organizationId}/oauth2/authorize} to the configured {@code consentPage}
 * only ever forwards {@code scope}/{@code client_id}/{@code state} — {@code display=modal} on the
 * <em>original</em> authorize request is silently dropped across that redirect, so a direct query
 * check alone (what the login page relies on) is never satisfied on the consent page in practice.
 * Dropping the gate instead was tried once and reverted: {@code
 * AuthorizationCodeFlowIntegrationTest} live-caught it relaxing {@code frame-ancestors} to {@code
 * '*'} for every ordinary, non-modal consent render in a development-tier Organization, not just
 * genuinely embedded ones. Real fix: {@link #captureModalStateIfPresent} stashes the original
 * authorize request's own {@code state} value into the {@code HttpSession} the moment {@code
 * display=modal} is seen on {@link #AUTHORIZE_PATH} — the same {@code HttpSession}-attribute idiom
 * {@code DeviceTrustGate}/{@code SessionTaskGate} already establish — and {@link
 * #withRelaxedFrameAncestorsOnConsentPage} matches it back against the consent render's own {@code
 * state} before relaxing. Keyed by {@code state}, not a bare boolean, specifically so a second,
 * unrelated, non-modal consent render later in the same browser session can never inherit a stale
 * "was modal once" flag — the exact shape of regression the reverted blanket-drop attempt above
 * hit.
 *
 * <p><b>Security finding, 2026-10-07 (live validation of TD-FUT-045, the embedded-profile extension
 * of this same mechanism): {@code EmbeddingEligibilityChecker} now takes the request path's own
 * Organization too.</b> The relaxation above used to resolve an allowed origin from {@code
 * clientId} alone — nothing checked that the resolved {@code OAuthClient} actually belonged to the
 * Organization whose login/profile page was being framed. An attacker who legitimately registers
 * their own verified-domain {@code OAuthClient} under their own Organization could have framed a
 * *different* Organization's own login or profile page inside their own site — a real cross-tenant
 * clickjacking setup {@code threat-model-stride.md}'s own existing entry for this relaxation never
 * named. Same {@code organizationId}-cross-check posture this codebase already applies elsewhere
 * for an identical reason ({@code DeviceTrustChallengeController}, BR-ORG-02). {@link
 * #organizationIdFromPath} parses it straight from the request path for both {@link
 * #LOGIN_PAGE_PATH}/{@link #ACCOUNT_PROFILE_PAGE_PATH} (both genuinely {@code
 * "/o/{organizationId}/..."} shaped) and fails CLOSED (no relaxation attempted at all) if that ever
 * comes back null — never silently falls through to the one call site ({@link
 * #withRelaxedFrameAncestorsOnConsentPage}, the flat/org-agnostic consent page) where a null is a
 * deliberate, unrelated opt-out.
 *
 * <p><b>ADR-0025: the admin dashboard's own policy.</b> {@code /platform/dashboard/**} gets {@code
 * script-src 'self'} — same shape as {@link #LOGIN_PAGE_POLICY}, for the same reason: a real,
 * same-origin, project-vendored script (HTMX, self-hosted under {@code /js/htmx.min.js}, never a
 * CDN), never {@code 'unsafe-inline'}/{@code 'unsafe-eval'} (HTMX's own attribute-driven model
 * needs neither — confirmed against its own docs, not assumed). Deliberately its own named policy,
 * not a reuse of {@code LOGIN_PAGE_POLICY} — the two happen to be identical today, but they relax
 * for structurally different reasons (one script tag vs. a whole app shell) and this project's own
 * established convention ({@code STRICT_POLICY} vs. {@code LOGIN_PAGE_POLICY} themselves) is a
 * named constant per real reason, not one shared just because the text matches right now.
 *
 * <p><b>Security finding, 2026-10-09 (live-caught on preproduction, real browser): {@code
 * form-action 'self'} widens to the requesting {@code OAuthClient}'s own registered {@code
 * redirectUri} origin(s) on the login and consent pages.</b> Both pages' own {@code <form>} POST
 * ultimately ends, after SAS's own internal redirects, in a 302 to that {@code redirect_uri} —
 * never this origin, by protocol design. Chrome and Safari enforce {@code form-action} against
 * every redirect hop a form submission's own navigation passes through, not just the literal {@code
 * action} attribute (Firefox does not); {@code curl}/{@code HttpClient}-based tests, {@code
 * AuthorizationCodeFlowIntegrationTest} included, never enforce CSP at all, which is exactly how
 * this shipped unnoticed since TD-SEC-009 first added the header. See {@link
 * RedirectUriOriginResolver}'s own Javadoc for the real-world reports confirming this browser
 * behavior and the full fix shape — same {@code clientId}/{@code expectedOrganizationId}
 * cross-tenant-check posture {@link EmbeddingEligibilityChecker} already establishes for {@code
 * frame-ancestors}, reused verbatim here for {@code form-action} instead, never widened to every
 * Organization's own registered clients.
 */
// PMD.AvoidDuplicateLiterals: the repeated string is "PMD.LongVariable" itself, used on 4 of this
// class's own long, descriptively-named constants — same false-positive rationale
// OrganizationAuthorizationServerConfig's own identical class-level suppression documents.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class ContentSecurityPolicyHeaderWriter implements HeaderWriter {

  private static final String HEADER_NAME = "Content-Security-Policy";
  private static final String DISPLAY_PARAM = "display";
  private static final String DISPLAY_MODAL = "modal";

  private static final String SELF_IMAGES = "img-src 'self'";

  // https://host or https://host:port, nothing else: no path, no query, no ';', no whitespace.
  private static final Pattern HTTPS_ORIGIN =
      Pattern.compile("^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$");

  // This project's own login-page-only query param convention — never SAS's own client_id.
  private static final String CLIENT_ID_PARAM = "clientId";

  // OAuth2's own spec parameter name (RFC 6749 §4.1.1) — what SAS's own consent redirect actually
  // carries. See this class's own Javadoc for why the consent page can't reuse CLIENT_ID_PARAM.
  @SuppressWarnings("PMD.LongVariable")
  private static final String OAUTH2_CLIENT_ID_PARAM = "client_id";

  // font-src 'self' on every policy below: clavaris.css self-hosts Geist (/fonts/*.woff2). With
  // font-src 'none' the browser blocked both files, so the whole UI silently fell back to the
  // system font. Same-origin fonts only — never a font CDN.
  private static final String STRICT_POLICY =
      "default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // Matches only ConsentController's own flat, org-agnostic GET — see CONSENT_PATH_PATTERN's own
  // comment in OrganizationAuthorizationServerConfig for why this is not "/o/*/oauth2/consent".
  // Never the platform tier (client_credentials only, BR-PLATFORM-01, no interactive consent to
  // render).
  private static final Pattern CONSENT_PAGE_PATH = Pattern.compile("^/oauth2/consent$");

  // TD-SEC-050: the one point in the whole flow that reliably sees display=modal on an
  // authenticated (or about-to-authenticate) request before SAS's own sendAuthorizationConsent
  // drops it on its internal redirect to CONSENT_PAGE_PATH above — see this class's own Javadoc
  // addendum for the full flow this closes.
  private static final Pattern AUTHORIZE_PATH = Pattern.compile("^/o/[^/]+/oauth2/authorize$");

  // Keyed by this specific authorization attempt's own OAuth2 "state" value (a fresh, single-use
  // nonce per RFC 6749 §10.12) rather than a bare boolean — see
  // withRelaxedFrameAncestorsOnConsentPage for why a boolean would risk relaxing a later,
  // unrelated, non-modal consent render sharing the same HttpSession (exactly the
  // AuthorizationCodeFlowIntegrationTest-caught regression this class's own Javadoc documents for
  // a blanket relaxation).
  @SuppressWarnings("PMD.LongVariable")
  private static final String MODAL_STATE_SESSION_ATTRIBUTE =
      "clavaris.security.display-modal.pending-state";

  // TD-SEC-009 addendum, see this class's own Javadoc: the one project-owned template that now
  // loads a real, same-origin script.
  //
  // connect-src 'self', not 'none' (TD-FUT-034, same DASHBOARD_PAGE_POLICY bug class found live
  // 2026-09-16, now repeated here deliberately): webauthn-login.js's own "Sign in with a passkey"
  // button issues fetch() calls to /login/webauthn/start and /login/webauthn/finish — governed by
  // connect-src, never script-src. script-src 'self' only permits loading the script file itself.
  private static final String LOGIN_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // Matches only LoginController's own GET/POST /o/{organizationId}/login — never
  // /o/*/login/social/** (SocialLoginConfig's plain links, nothing to double-submit) or the
  // platform tier's own login template (a different page, no such script).
  private static final Pattern LOGIN_PAGE_PATH = Pattern.compile("^/o/[^/]+/login$");

  // ADR-0025: same relaxation, own named policy — see this class's own Javadoc for why not reused.
  //
  // connect-src 'self', not 'none' (SDE-III review, 2026-09-16 — real bug, found live): every
  // mutation and every paginated list on this whole dashboard (register/deactivate/rotate-secret,
  // Organizations, Workspaces, OAuth Clients, Secret Keys, ...) is an HTMX hx-post/hx-get, which
  // issues its request via fetch()/XHR — governed by CSP's connect-src, never script-src.
  // script-src 'self' only permits loading /js/htmx.min.js itself, not the requests that script
  // makes. With connect-src 'none' (copied from STRICT_POLICY/LOGIN_PAGE_POLICY, neither of which
  // makes any fetch call at all), the browser silently blocks every single HTMX request on this
  // chain — no network entry, no visible error unless the operator opens the console — confirmed
  // live against a real browser's own CSP violation report on this exact directive.
  @SuppressWarnings("PMD.LongVariable")
  private static final String DASHBOARD_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; "
          + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // img-src gains blob: here and nowhere else: profile-picture-upload.js previews the file a person
  // has just chosen (URL.createObjectURL) before anything is uploaded. A blob: URL can only be
  // minted by a script of this same origin, so it opens no new remote image source.
  //
  // Matches every page under the dashboard app shell — PlatformDashboardSecurityConfig's own
  // securityMatcher already scopes the whole /platform/** chain to ROLE_PLATFORM_ACCOUNT (this
  // sub-path included), so this pattern only needs to distinguish "dashboard" from "login/register/
  // forgot-password" on that same chain, not re-enforce authentication itself.
  @SuppressWarnings("PMD.LongVariable")
  private static final Pattern DASHBOARD_PAGE_PATH = Pattern.compile("^/platform/dashboard(/.*)?$");

  // TD-FUT-034: the self-service "your passkeys" page's own webauthn-register.js needs both
  // script-src 'self' (to load at all — every other page outside the three named patterns here
  // falls through to STRICT_POLICY's own script-src 'none', which would block the script entirely)
  // and connect-src 'self' (its registration/start and registration/finish fetch() calls) — same
  // two-directive fix LOGIN_PAGE_POLICY's own identical comment documents, scoped to just this one
  // new page rather than widening to all of /o/*/account/** (no sibling self-service page uses a
  // script today).
  @SuppressWarnings("PMD.LongVariable")
  private static final String ACCOUNT_PASSKEYS_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  @SuppressWarnings("PMD.LongVariable")
  private static final Pattern ACCOUNT_PASSKEYS_PAGE_PATH =
      Pattern.compile("^/o/[^/]+/account/passkeys$");

  // Clerk <UserProfile/> parity, pattern (a) — embedded profile, same ADR-0009 §1/§4 mechanism as
  // LOGIN_PAGE_POLICY right above, deliberately not CORS/a new JS SDK (that would reopen ADR-0013's
  // own locked "no cross-origin browser caller" decision — confirmed with the product owner,
  // 2026-10-07): a consuming application iframes this same self-service page
  // (identity/account/profile.html) with ?display=modal&clientId=... on the src URL, exactly the
  // login page's own query-param convention, reusing EmbeddingEligibilityChecker verbatim (no
  // change needed here for this). Real single sign-on, not merely "the embedded login's own first
  // session survives" (real bug found live, 2026-10-10, closed in
  // AdaptiveSameSiteSessionCookieSerializer, not here): an Account that already has an ordinary,
  // non-embedded, top-level session on a real HTTPS deployment is now recognized by this iframe
  // directly, no second login inside it required — only a genuinely brand-new browser (no live
  // Clavaris session at all yet) sees Clavaris's own login form inside the iframe first.
  //
  // script-src 'self' (this template's own i18n.js/organization-dialog.js, same two scripts
  // every dashboard-adjacent page already loads), connect-src 'none' (neither script makes a
  // fetch/XHR call — confirmed by reading organization-dialog.js directly, no HTMX attributes
  // anywhere in this specific template unlike the dashboard's own pages).
  @SuppressWarnings("PMD.LongVariable")
  private static final String ACCOUNT_PROFILE_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  @SuppressWarnings("PMD.LongVariable")
  private static final Pattern ACCOUNT_PROFILE_PAGE_PATH =
      Pattern.compile("^/o/[^/]+/account/profile$");

  // See this class's own Javadoc (2026-10-07 security finding) for why this exists. Reused by
  // both login/profile — LOGIN_PAGE_PATH/ACCOUNT_PROFILE_PAGE_PATH already proved the shape, this
  // just captures the same segment.
  @SuppressWarnings("PMD.LongVariable")
  private static final Pattern ORGANIZATION_SCOPED_PATH_PREFIX = Pattern.compile("^/o/([^/]+)/.*$");

  private final EmbeddingEligibilityChecker embeddingChecker;

  // Security finding, 2026-10-09: see this class's own Javadoc addendum.
  @SuppressWarnings("PMD.LongVariable")
  private final RedirectUriOriginResolver redirectUriOriginResolver;

  // Constructed only by each SecurityFilterChain builder's own `new
  // ContentSecurityPolicyHeaderWriter(checker, resolver)` call — see this class's own Javadoc for
  // why every site passes one even though only OrganizationAuthorizationServerConfig's own chain
  // ever actually invokes it.
  public ContentSecurityPolicyHeaderWriter(
      final EmbeddingEligibilityChecker embeddingChecker,
      @SuppressWarnings("PMD.LongVariable")
          final RedirectUriOriginResolver redirectUriOriginResolver) {
    this.embeddingChecker = embeddingChecker;
    this.redirectUriOriginResolver = redirectUriOriginResolver;
  }

  @Override
  public void writeHeaders(final HttpServletRequest request, final HttpServletResponse response) {
    // TD-SEC-050: runs unconditionally, before the isHtml/already-set early return below — the
    // authorize request's own response is SAS's own 302 redirect (never text/html), so gating this
    // capture behind the same check the header-writing logic uses would mean it never fires at all.
    captureModalStateIfPresent(request);
    if (response.containsHeader(HEADER_NAME) || !isHtml(response)) {
      return;
    }
    response.setHeader(HEADER_NAME, withLogoOrigin(policyFor(request), request));
  }

  // An application's logo is an https URL on its own origin, which img-src 'self' would make the
  // browser refuse: the sign-in and consent pages would show a broken image instead. The page's
  // controller names that one origin on the request; this adds exactly it to img-src for this
  // response and nothing else. Anything that is not a plain https://host[:port] is ignored, so a
  // value could never smuggle another directive into the policy.
  private static String withLogoOrigin(final String policy, final HttpServletRequest request) {
    final Object origin = request.getAttribute(ConsumerBrandNameInterceptor.LOGO_ORIGIN);
    return origin instanceof String candidate && HTTPS_ORIGIN.matcher(candidate).matches()
        ? policy.replace(SELF_IMAGES, SELF_IMAGES + " " + candidate)
        : policy;
  }

  // TD-SEC-050: fires on every request to AUTHORIZE_PATH, authenticated or not — an unauthenticated
  // first hit still passes through HeaderWriterFilter before ExceptionTranslationFilter redirects
  // it to login, and Spring Security's own default changeSessionId() (session-fixation protection
  // at login) preserves this same session object's attributes across the ID rotation, so capturing
  // this early survives login intact. Capturing again on the post-login, RequestCache-replayed hit
  // to this same path is harmless (same value, or nothing to capture if display=modal is genuinely
  // absent this time).
  private static void captureModalStateIfPresent(final HttpServletRequest request) {
    if (!AUTHORIZE_PATH.matcher(request.getRequestURI()).matches()) {
      return;
    }
    final String state = request.getParameter(OAuth2ParameterNames.STATE);
    if (DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM)) && state != null) {
      request.getSession(true).setAttribute(MODAL_STATE_SESSION_ATTRIBUTE, state);
    }
  }

  // Five-way, not a ternary any more — see this class's own Javadoc for why each path pattern
  // gets its own real policy/relaxation rule rather than one being folded into "everything else".
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String policyFor(final HttpServletRequest request) {
    final String requestUri = request.getRequestURI();
    if (CONSENT_PAGE_PATH.matcher(requestUri).matches()) {
      return withWidenedFormAction(
          withRelaxedFrameAncestorsOnConsentPage(request),
          request.getParameter(OAUTH2_CLIENT_ID_PARAM),
          null);
    }
    if (LOGIN_PAGE_PATH.matcher(requestUri).matches()) {
      final UUID organizationId = organizationIdFromPath(requestUri);
      return withWidenedFormAction(
          withRelaxedFrameAncestorsIfDisplayModal(
              LOGIN_PAGE_POLICY, request, CLIENT_ID_PARAM, organizationId),
          request.getParameter(CLIENT_ID_PARAM),
          organizationId);
    }
    if (DASHBOARD_PAGE_PATH.matcher(requestUri).matches()) {
      return DASHBOARD_PAGE_POLICY;
    }
    if (ACCOUNT_PASSKEYS_PAGE_PATH.matcher(requestUri).matches()) {
      return ACCOUNT_PASSKEYS_PAGE_POLICY;
    }
    if (ACCOUNT_PROFILE_PAGE_PATH.matcher(requestUri).matches()) {
      return withRelaxedFrameAncestorsIfDisplayModal(
          ACCOUNT_PROFILE_PAGE_POLICY,
          request,
          CLIENT_ID_PARAM,
          organizationIdFromPath(requestUri));
    }
    return STRICT_POLICY;
  }

  // ADR-0009 §1/§4: see this class's own Javadoc. STRICT_POLICY/LOGIN_PAGE_POLICY both end in the
  // exact literal "frame-ancestors 'none'" — asserted by construction, not discovered by parsing.
  // PMD.OnlyOneReturn: three independent, equally valid exits — "not display=modal at all," the
  // new fail-closed-on-malformed-segment middle one (security finding, 2026-10-07), and "resolved"
  // — same rationale as every other early-return chain in this codebase. PMD.LongVariable: see
  // EmbeddingEligibilityChecker's own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LongVariable"})
  private String withRelaxedFrameAncestorsIfDisplayModal(
      final String basePolicy,
      final HttpServletRequest request,
      final String clientIdParam,
      final UUID expectedOrganizationId) {
    if (!DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      return basePolicy;
    }
    // Security finding, 2026-10-07: this method is only ever called for a request this class's
    // own LOGIN_PAGE_PATH/ACCOUNT_PROFILE_PAGE_PATH already matched — both genuinely
    // "/o/{organizationId}/..." shaped, so expectedOrganizationId should never actually be null
    // here. If it somehow is (a malformed segment HeaderWriterFilter sees before Spring MVC's own
    // @PathVariable UUID binding would reject it), fail CLOSED — no relaxation at all — never
    // fall through to relaxFrameAncestors, where a bare null is the signal
    // withRelaxedFrameAncestorsOnConsentPage uses on purpose to mean "no Organization to check
    // against," the opposite intent.
    if (expectedOrganizationId == null) {
      return basePolicy;
    }
    return relaxFrameAncestors(
        basePolicy, request.getParameter(clientIdParam), expectedOrganizationId);
  }

  // Security finding, 2026-10-07: null on a malformed/missing segment — fail-safe, same posture
  // every other UUID.fromString call site in this class's own neighborhood (e.g. this class's own
  // callers never trust a client-suppliable value without a try/catch around it) already follows.
  // Only ever called for a request this same method's own caller already matched against
  // LOGIN_PAGE_PATH/ACCOUNT_PROFILE_PAGE_PATH, both "/o/{organizationId}/..." shaped, so the
  // capturing group below is expected to be present and well-formed in practice — this is a
  // defensive fallback, not the normal path. PMD.OnlyOneReturn: "doesn't even match the shape" /
  // "malformed UUID" / "resolved" are three independent, equally valid exits.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static UUID organizationIdFromPath(final String requestUri) {
    final Matcher matcher = ORGANIZATION_SCOPED_PATH_PREFIX.matcher(requestUri);
    if (!matcher.matches()) {
      return null;
    }
    try {
      return UUID.fromString(matcher.group(1));
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  // TD-SEC-050 (closed): the consent page needs its own variant, not the shared method above —
  // SAS's own sendAuthorizationConsent redirect never forwards display=modal, so a direct query
  // check alone (what the login page relies on) is never satisfied here in practice. Falls back to
  // the session state captureModalStateIfPresent stashed on the original /oauth2/authorize request,
  // matched against this exact consent render's own "state" value — never a bare "was modal ever
  // seen this session" flag, which would risk relaxing a later, unrelated, non-modal consent render
  // sharing the same HttpSession (the precise regression AuthorizationCodeFlowIntegrationTest
  // caught before this gate existed at all — see this class's own Javadoc).
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String withRelaxedFrameAncestorsOnConsentPage(final HttpServletRequest request) {
    // /oauth2/consent is flat/org-agnostic (this class's own Javadoc) — genuinely no
    // organizationId to check against, the one deliberate null EmbeddingEligibilityChecker's own
    // Javadoc documents as an opt-out, not a gap matching the one
    // withRelaxedFrameAncestorsIfDisplayModal
    // just closed.
    if (DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      return relaxFrameAncestors(STRICT_POLICY, request.getParameter(OAUTH2_CLIENT_ID_PARAM), null);
    }
    final HttpSession session = request.getSession(false);
    final String pendingState =
        session == null ? null : (String) session.getAttribute(MODAL_STATE_SESSION_ATTRIBUTE);
    if (pendingState != null
        && Objects.equals(pendingState, request.getParameter(OAuth2ParameterNames.STATE))) {
      return relaxFrameAncestors(STRICT_POLICY, request.getParameter(OAUTH2_CLIENT_ID_PARAM), null);
    }
    return STRICT_POLICY;
  }

  @SuppressWarnings("PMD.LongVariable")
  private String relaxFrameAncestors(
      final String basePolicy, final String clientId, final UUID expectedOrganizationId) {
    final Optional<String> allowedOrigin =
        embeddingChecker.resolveAllowedFrameAncestor(clientId, expectedOrganizationId);
    return allowedOrigin
        .map(origin -> basePolicy.replace("frame-ancestors 'none'", "frame-ancestors " + origin))
        .orElse(basePolicy);
  }

  // Security finding, 2026-10-09: see this class's own Javadoc addendum. Unlike
  // relaxFrameAncestors above (gated behind display=modal via the two
  // withRelaxedFrameAncestors*/withWidenedFormAction's own callers), this widening is
  // unconditional — every ordinary, non-embedded login/consent also ends in a cross-origin
  // redirect to the client's own redirect_uri, not just a modal-embedded one.
  // PMD.OnlyOneReturn: "nothing resolved, keep the base policy" / "widen it" are two genuinely
  // distinct outcomes — same rationale every other early-return guard in this class already
  // applies.
  @SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
  private String withWidenedFormAction(
      final String basePolicy, final String clientId, final UUID expectedOrganizationId) {
    final List<String> allowedOrigins =
        redirectUriOriginResolver.resolveAllowedFormActionOrigins(clientId, expectedOrganizationId);
    if (allowedOrigins.isEmpty()) {
      return basePolicy;
    }
    return basePolicy.replace(
        "form-action 'self'", "form-action 'self' " + String.join(" ", allowedOrigins));
  }

  private static boolean isHtml(final HttpServletResponse response) {
    final String contentType = response.getContentType();
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
  }
}
