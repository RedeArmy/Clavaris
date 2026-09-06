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
 * <p>Three policies, not one, because this project's own templates and Spring Authorization
 * Server's own default consent page have genuinely different real needs — confirmed by decompiling
 * SAS's {@code DefaultConsentPage} (spring-security-oauth2-authorization-server 7.1.0), not
 * assumed: every template this project owns other than the login page below (grep confirms zero
 * {@code <script>}, {@code <style>}, or external-host reference anywhere else under {@code
 * identity-module}'s/{@code organization-module}'s own {@code resources/templates}) gets the strict
 * policy; SAS's own unbranded consent page (TD-SEC-011, still open) loads Bootstrap from a CDN
 * (with Subresource Integrity already on that {@code <link>} tag) and uses one inline {@code
 * <script>} plus {@code onclick} handlers for its Cancel button — a real, external requirement of
 * code this project doesn't own the source of, scoped as narrowly as that specific page's own real
 * needs allow (one named CDN host, not wildcarded; {@code 'unsafe-inline'} only for scripts, the
 * one directive SAS's page can't function without). <b>Investigating this originally surfaced
 * TD-SEC-026</b> — {@code requireAuthorizationConsent} was never set to {@code true} anywhere, so
 * this page structurally never rendered for any client. That's now closed (ADR-0017, TD-SEC-026):
 * consent is a real, per-client {@code OAuthClient} attribute, defaulting to required, and this
 * branch is live-verified against an actually-rendered consent screen (see {@code
 * AuthorizationCodeFlowIntegrationTest}'s own consent-required test), not just unit-tested
 * path-matching. Stays correctly scoped for the day ADR-0009 replaces this page with a
 * project-owned, branded one (TD-SEC-011, still open).
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
 * <p>ADR-0009 §1/§4: on the login/consent pages only, {@code display=modal} + a {@code clientId}
 * query param resolved as embedding-eligible by {@link EmbeddingEligibilityChecker} relaxes {@code
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
 */
final class ContentSecurityPolicyHeaderWriter implements HeaderWriter {

  private static final String HEADER_NAME = "Content-Security-Policy";
  private static final String DISPLAY_PARAM = "display";
  private static final String DISPLAY_MODAL = "modal";
  private static final String CLIENT_ID_PARAM = "clientId";

  private static final String STRICT_POLICY =
      "default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; "
          + "font-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // TD-SEC-011: see this class's own Javadoc for exactly why this is weaker, and why only here.
  @SuppressWarnings("PMD.LongVariable")
  private static final String CONSENT_PAGE_POLICY =
      "default-src 'self'; script-src 'self' 'unsafe-inline'; "
          + "style-src 'self' https://stackpath.bootstrapcdn.com; img-src 'self'; "
          + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // Matches only SAS's own consent-rendering GET — /o/{organizationId}/oauth2/authorize — never
  // the platform tier (client_credentials only, BR-PLATFORM-01, no interactive consent to render).
  private static final Pattern CONSENT_PAGE_PATH = Pattern.compile("^/o/[^/]+/oauth2/authorize$");

  // TD-SEC-009 addendum, see this class's own Javadoc: the one project-owned template that now
  // loads a real, same-origin script. Unlike CONSENT_PAGE_POLICY above, this name is short enough
  // that PMD's LongVariable rule never flags it — no suppression needed.
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
  // gets its own real policy rather than one being folded into "everything else".
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String policyFor(final HttpServletRequest request) {
    final String requestUri = request.getRequestURI();
    if (CONSENT_PAGE_PATH.matcher(requestUri).matches()) {
      return withRelaxedFrameAncestorsIfEligible(CONSENT_PAGE_POLICY, request);
    }
    if (LOGIN_PAGE_PATH.matcher(requestUri).matches()) {
      return withRelaxedFrameAncestorsIfEligible(LOGIN_PAGE_POLICY, request);
    }
    return STRICT_POLICY;
  }

  // ADR-0009 §1/§4: see this class's own Javadoc. Every policy this method is ever called with
  // ends in the exact literal "frame-ancestors 'none'" — asserted by construction, not
  // discovered by parsing, since both callers pass one of this class's own two constants.
  // PMD.OnlyOneReturn: "not display=modal at all" / "resolved" are two independent, equally valid
  // exits — same rationale as every other early-return chain in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String withRelaxedFrameAncestorsIfEligible(
      final String basePolicy, final HttpServletRequest request) {
    if (!DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      return basePolicy;
    }
    final Optional<String> allowedOrigin =
        embeddingChecker.resolveAllowedFrameAncestor(request.getParameter(CLIENT_ID_PARAM));
    return allowedOrigin
        .map(origin -> basePolicy.replace("frame-ancestors 'none'", "frame-ancestors " + origin))
        .orElse(basePolicy);
  }

  private static boolean isHtml(final HttpServletResponse response) {
    final String contentType = response.getContentType();
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
  }
}
