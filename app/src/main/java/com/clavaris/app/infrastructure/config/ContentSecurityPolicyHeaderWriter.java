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
 * the plain {@link #ContentSecurityPolicies.STRICT_POLICY}, the same as every other template this
 * project owns other than the login page (grep confirms zero {@code <script>} anywhere under {@code
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
 * {@link #ContentSecurityPolicies.LOGIN_PAGE_PATH}/{@link
 * #ContentSecurityPolicies.CONSENT_PAGE_PATH}, so the checker there is never actually invoked.
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
 * display=modal} is seen on {@link #ContentSecurityPolicies.AUTHORIZE_PATH} — the same {@code
 * HttpSession}-attribute idiom {@code DeviceTrustGate}/{@code SessionTaskGate} already establish —
 * and {@link #withRelaxedFrameAncestorsOnConsentPage} matches it back against the consent render's
 * own {@code state} before relaxing. Keyed by {@code state}, not a bare boolean, specifically so a
 * second, unrelated, non-modal consent render later in the same browser session can never inherit a
 * stale "was modal once" flag — the exact shape of regression the reverted blanket-drop attempt
 * above hit.
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
 * #ContentSecurityPolicies.LOGIN_PAGE_PATH}/{@link
 * #ContentSecurityPolicies.ACCOUNT_PROFILE_PAGE_PATH} (both genuinely {@code
 * "/o/{organizationId}/..."} shaped) and fails CLOSED (no relaxation attempted at all) if that ever
 * comes back null — never silently falls through to the one call site ({@link
 * #withRelaxedFrameAncestorsOnConsentPage}, the flat/org-agnostic consent page) where a null is a
 * deliberate, unrelated opt-out.
 *
 * <p><b>ADR-0025: the admin dashboard's own policy.</b> {@code /platform/dashboard/**} gets {@code
 * script-src 'self'} — same shape as {@link #ContentSecurityPolicies.LOGIN_PAGE_POLICY}, for the
 * same reason: a real, same-origin, project-vendored script (HTMX, self-hosted under {@code
 * /js/htmx.min.js}, never a CDN), never {@code 'unsafe-inline'}/{@code 'unsafe-eval'} (HTMX's own
 * attribute-driven model needs neither — confirmed against its own docs, not assumed). Deliberately
 * its own named policy, not a reuse of {@code ContentSecurityPolicies.LOGIN_PAGE_POLICY} — the two
 * happen to be identical today, but they relax for structurally different reasons (one script tag
 * vs. a whole app shell) and this project's own established convention ({@code
 * ContentSecurityPolicies.STRICT_POLICY} vs. {@code ContentSecurityPolicies.LOGIN_PAGE_POLICY}
 * themselves) is a named constant per real reason, not one shared just because the text matches
 * right now.
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
public final class ContentSecurityPolicyHeaderWriter implements HeaderWriter {

  private static final String HEADER_NAME = "Content-Security-Policy";
  private static final String DISPLAY_PARAM = "display";
  private static final String DISPLAY_MODAL = "modal";

  private static final String SELF_IMAGES = "img-src 'self'";

  // https://host or https://host:port, nothing else: no path, no query, no ';', no whitespace.
  private static final Pattern HTTPS_ORIGIN =
      Pattern.compile("^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?$");

  // This project's own login-page-only query param convention — never SAS's own client_id.
  private static final String CLIENT_ID_PARAM = "clientId";

  // OAuth2's own spec parameter name (RFC 6749 §4.1.1) — what SAS's own consent redirect actually
  // carries. See this class's own Javadoc for why the consent page can't reuse CLIENT_ID_PARAM.
  private static final String OAUTH2_CLIENT_ID_PARAM = "client_id";

  // Keyed by this specific authorization attempt's own OAuth2 "state" value (a fresh, single-use
  // nonce per RFC 6749 §10.12) rather than a bare boolean — see
  // withRelaxedFrameAncestorsOnConsentPage for why a boolean would risk relaxing a later,
  // unrelated, non-modal consent render sharing the same HttpSession (exactly the
  // AuthorizationCodeFlowIntegrationTest-caught regression this class's own Javadoc documents for
  // a blanket relaxation).
  private static final String MODAL_STATE_SESSION_ATTRIBUTE =
      "clavaris.security.display-modal.pending-state";

  private final EmbeddingEligibilityChecker embeddingChecker;

  // Security finding, 2026-10-09: see this class's own Javadoc addendum.
  private final RedirectUriOriginResolver redirectUriOriginResolver;

  // Constructed only by each SecurityFilterChain builder's own `new
  // ContentSecurityPolicyHeaderWriter(checker, resolver)` call — see this class's own Javadoc for
  // why every site passes one even though only OrganizationAuthorizationServerConfig's own chain
  // ever actually invokes it.
  public ContentSecurityPolicyHeaderWriter(
      final EmbeddingEligibilityChecker embeddingChecker,
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

  // TD-SEC-050: fires on every request to ContentSecurityPolicies.AUTHORIZE_PATH, authenticated or
  // not — an unauthenticated
  // first hit still passes through HeaderWriterFilter before ExceptionTranslationFilter redirects
  // it to login, and Spring Security's own default changeSessionId() (session-fixation protection
  // at login) preserves this same session object's attributes across the ID rotation, so capturing
  // this early survives login intact. Capturing again on the post-login, RequestCache-replayed hit
  // to this same path is harmless (same value, or nothing to capture if display=modal is genuinely
  // absent this time).
  private static void captureModalStateIfPresent(final HttpServletRequest request) {
    if (!ContentSecurityPolicies.AUTHORIZE_PATH.matcher(request.getRequestURI()).matches()) {
      return;
    }
    final String state = request.getParameter(OAuth2ParameterNames.STATE);
    if (DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM)) && state != null) {
      request.getSession(true).setAttribute(MODAL_STATE_SESSION_ATTRIBUTE, state);
    }
  }

  // Five-way, not a ternary any more — see this class's own Javadoc for why each path pattern
  // gets its own real policy/relaxation rule rather than one being folded into "everything else".
  private String policyFor(final HttpServletRequest request) {
    final String requestUri = request.getRequestURI();
    if (ContentSecurityPolicies.CONSENT_PAGE_PATH.matcher(requestUri).matches()) {
      return withWidenedFormAction(
          withRelaxedFrameAncestorsOnConsentPage(request),
          request.getParameter(OAUTH2_CLIENT_ID_PARAM),
          null);
    }
    if (ContentSecurityPolicies.LOGIN_PAGE_PATH.matcher(requestUri).matches()) {
      final UUID organizationId = organizationIdFromPath(requestUri);
      return withWidenedFormAction(
          withRelaxedFrameAncestorsIfDisplayModal(
              ContentSecurityPolicies.LOGIN_PAGE_POLICY, request, CLIENT_ID_PARAM, organizationId),
          request.getParameter(CLIENT_ID_PARAM),
          organizationId);
    }
    if (ContentSecurityPolicies.DASHBOARD_PAGE_PATH.matcher(requestUri).matches()) {
      return ContentSecurityPolicies.DASHBOARD_PAGE_POLICY;
    }
    if (ContentSecurityPolicies.ACCOUNT_PASSKEYS_PAGE_PATH.matcher(requestUri).matches()) {
      return ContentSecurityPolicies.ACCOUNT_PASSKEYS_PAGE_POLICY;
    }
    if (ContentSecurityPolicies.ACCOUNT_PROFILE_PAGE_PATH.matcher(requestUri).matches()) {
      return withRelaxedFrameAncestorsIfDisplayModal(
          ContentSecurityPolicies.ACCOUNT_PROFILE_PAGE_POLICY,
          request,
          CLIENT_ID_PARAM,
          organizationIdFromPath(requestUri));
    }
    if (ContentSecurityPolicies.ORGANIZATION_SCOPED_PATH_PREFIX.matcher(requestUri).matches()) {
      return ContentSecurityPolicies.CONSUMER_POLICY;
    }
    return ContentSecurityPolicies.STRICT_POLICY;
  }

  private String withRelaxedFrameAncestorsIfDisplayModal(
      final String basePolicy,
      final HttpServletRequest request,
      final String clientIdParam,
      final UUID expectedOrganizationId) {
    if (!DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      return basePolicy;
    }
    // Security finding, 2026-10-07: this method is only ever called for a request this class's
    // own ContentSecurityPolicies.LOGIN_PAGE_PATH/ContentSecurityPolicies.ACCOUNT_PROFILE_PAGE_PATH
    // already matched — both genuinely
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

  private static UUID organizationIdFromPath(final String requestUri) {
    final Matcher matcher =
        ContentSecurityPolicies.ORGANIZATION_SCOPED_PATH_PREFIX.matcher(requestUri);
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
  private String withRelaxedFrameAncestorsOnConsentPage(final HttpServletRequest request) {
    // /oauth2/consent is flat/org-agnostic (this class's own Javadoc) — genuinely no
    // organizationId to check against, the one deliberate null EmbeddingEligibilityChecker's own
    // Javadoc documents as an opt-out, not a gap matching the one
    // withRelaxedFrameAncestorsIfDisplayModal
    // just closed.
    if (DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      return relaxFrameAncestors(
          ContentSecurityPolicies.CONSUMER_POLICY,
          request.getParameter(OAUTH2_CLIENT_ID_PARAM),
          null);
    }
    final HttpSession session = request.getSession(false);
    final String pendingState =
        session == null ? null : (String) session.getAttribute(MODAL_STATE_SESSION_ATTRIBUTE);
    if (pendingState != null
        && Objects.equals(pendingState, request.getParameter(OAuth2ParameterNames.STATE))) {
      return relaxFrameAncestors(
          ContentSecurityPolicies.CONSUMER_POLICY,
          request.getParameter(OAUTH2_CLIENT_ID_PARAM),
          null);
    }
    return ContentSecurityPolicies.CONSUMER_POLICY;
  }

  private String relaxFrameAncestors(
      final String basePolicy, final String clientId, final UUID expectedOrganizationId) {
    final Optional<String> allowedOrigin =
        embeddingChecker.resolveAllowedFrameAncestor(clientId, expectedOrganizationId);
    return allowedOrigin
        .map(origin -> basePolicy.replace("frame-ancestors 'none'", "frame-ancestors " + origin))
        .orElse(basePolicy);
  }

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
