package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
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
 * mismatch this pass fixes, not something introduced by it. Still gated on {@code display=modal}
 * like the login page, even though SAS's own internal redirect from {@code
 * /o/{organizationId}/oauth2/authorize} to the configured {@code consentPage} only ever forwards
 * {@code scope}/{@code client_id}/{@code state} — {@code display=modal} on the <em>original</em>
 * authorize request is silently dropped across that redirect, so this gate is, in practice, never
 * actually satisfied on the consent page today. Dropping the gate instead of documenting this was
 * tried and reverted: {@code AuthorizationCodeFlowIntegrationTest} live-caught it relaxing {@code
 * frame-ancestors} to {@code '*'} for every ordinary, non-modal consent render in a
 * development-tier Organization, not just genuinely embedded ones. Tracked as its own follow-up in
 * {@code technical-debt-register.md} (TD-SEC-011's own entry) rather than fixed here — it needs a
 * real session-based carry-over of the modal signal across SAS's own redirect (the same {@code
 * HttpSession}-attribute idiom {@code DeviceTrustGate}/{@code SessionTaskGate} already establish),
 * a separate, moderate-sized addition, not a same-pass fix for a same-day filter-chain change.
 */
final class ContentSecurityPolicyHeaderWriter implements HeaderWriter {

  private static final String HEADER_NAME = "Content-Security-Policy";
  private static final String DISPLAY_PARAM = "display";
  private static final String DISPLAY_MODAL = "modal";

  // This project's own login-page-only query param convention — never SAS's own client_id.
  private static final String CLIENT_ID_PARAM = "clientId";

  // OAuth2's own spec parameter name (RFC 6749 §4.1.1) — what SAS's own consent redirect actually
  // carries. See this class's own Javadoc for why the consent page can't reuse CLIENT_ID_PARAM.
  @SuppressWarnings("PMD.LongVariable")
  private static final String OAUTH2_CLIENT_ID_PARAM = "client_id";

  private static final String STRICT_POLICY =
      "default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; "
          + "font-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // Matches only ConsentController's own flat, org-agnostic GET — see CONSENT_PATH_PATTERN's own
  // comment in OrganizationAuthorizationServerConfig for why this is not "/o/*/oauth2/consent".
  // Never the platform tier (client_credentials only, BR-PLATFORM-01, no interactive consent to
  // render).
  private static final Pattern CONSENT_PAGE_PATH = Pattern.compile("^/oauth2/consent$");

  // TD-SEC-009 addendum, see this class's own Javadoc: the one project-owned template that now
  // loads a real, same-origin script.
  private static final String LOGIN_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
          + "font-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // Matches only LoginController's own GET/POST /o/{organizationId}/login — never
  // /o/*/login/social/** (SocialLoginConfig's plain links, nothing to double-submit) or the
  // platform tier's own login template (a different page, no such script).
  private static final Pattern LOGIN_PAGE_PATH = Pattern.compile("^/o/[^/]+/login$");

  private final EmbeddingEligibilityChecker embeddingChecker;

  // Constructed only by each SecurityFilterChain builder's own `new
  // ContentSecurityPolicyHeaderWriter(checker)` call — see this class's own Javadoc for why every
  // site passes one even though only OrganizationAuthorizationServerConfig's own chain ever
  // actually invokes it.
  /* package */ ContentSecurityPolicyHeaderWriter(
      final EmbeddingEligibilityChecker embeddingChecker) {
    this.embeddingChecker = embeddingChecker;
  }

  @Override
  public void writeHeaders(final HttpServletRequest request, final HttpServletResponse response) {
    if (response.containsHeader(HEADER_NAME) || !isHtml(response)) {
      return;
    }
    response.setHeader(HEADER_NAME, policyFor(request));
  }

  // Three-way, not a ternary any more — see this class's own Javadoc for why each path pattern
  // gets its own real policy/relaxation rule rather than one being folded into "everything else".
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String policyFor(final HttpServletRequest request) {
    final String requestUri = request.getRequestURI();
    if (CONSENT_PAGE_PATH.matcher(requestUri).matches()) {
      return withRelaxedFrameAncestorsIfDisplayModal(
          STRICT_POLICY, request, OAUTH2_CLIENT_ID_PARAM);
    }
    if (LOGIN_PAGE_PATH.matcher(requestUri).matches()) {
      return withRelaxedFrameAncestorsIfDisplayModal(LOGIN_PAGE_POLICY, request, CLIENT_ID_PARAM);
    }
    return STRICT_POLICY;
  }

  // ADR-0009 §1/§4: see this class's own Javadoc. STRICT_POLICY/LOGIN_PAGE_POLICY both end in the
  // exact literal "frame-ancestors 'none'" — asserted by construction, not discovered by parsing.
  // PMD.OnlyOneReturn: "not display=modal at all" / "resolved" are two independent, equally valid
  // exits — same rationale as every other early-return chain in this codebase.
  //
  // TD-SEC-011: still gated on display=modal for the consent page too, even though SAS's own
  // internal redirect from /oauth2/authorize to this page never forwards that query param — see
  // TD-SEC-011's own technical-debt-register.md entry, "consent page loses display=modal across
  // SAS's own redirect" for why that is a real, separately-tracked follow-up rather than fixed by
  // relaxing this gate. Keeping the gate here (rather than dropping it, which this class's own
  // AuthorizationCodeFlowIntegrationTest live-caught relaxing frame-ancestors to '*' for every
  // ordinary, non-modal consent render in a development-tier Organization) keeps today's default
  // safe and correct; only the client_id parameter name below is genuinely fixed by this pass.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String withRelaxedFrameAncestorsIfDisplayModal(
      final String basePolicy, final HttpServletRequest request, final String clientIdParam) {
    if (!DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      return basePolicy;
    }
    return relaxFrameAncestors(basePolicy, request.getParameter(clientIdParam));
  }

  private String relaxFrameAncestors(final String basePolicy, final String clientId) {
    final Optional<String> allowedOrigin = embeddingChecker.resolveAllowedFrameAncestor(clientId);
    return allowedOrigin
        .map(origin -> basePolicy.replace("frame-ancestors 'none'", "frame-ancestors " + origin))
        .orElse(basePolicy);
  }

  private static boolean isHtml(final HttpServletResponse response) {
    final String contentType = response.getContentType();
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
  }
}
