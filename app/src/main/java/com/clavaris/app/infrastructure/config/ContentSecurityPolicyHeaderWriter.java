package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
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
 * <p>Two policies, not one, because this project's own templates and Spring Authorization Server's
 * own default consent page have genuinely different real needs — confirmed by decompiling SAS's
 * {@code DefaultConsentPage} (spring-security-oauth2-authorization-server 7.1.0), not assumed:
 * every template this project owns (grep confirms zero {@code <script>}, {@code <style>}, or
 * external-host reference anywhere under {@code identity-module}'s/{@code organization-module}'s
 * own {@code resources/templates}) gets the strict policy below; SAS's own unbranded consent page
 * (TD-SEC-011, still open) loads Bootstrap from a CDN (with Subresource Integrity already on that
 * {@code <link>} tag) and uses one inline {@code <script>} plus {@code onclick} handlers for its
 * Cancel button — a real, external requirement of code this project doesn't own the source of,
 * scoped as narrowly as that specific page's own real needs allow (one named CDN host, not
 * wildcarded; {@code 'unsafe-inline'} only for scripts, the one directive SAS's page can't function
 * without). <b>Investigating this originally surfaced TD-SEC-026</b> — {@code
 * requireAuthorizationConsent} was never set to {@code true} anywhere, so this page structurally
 * never rendered for any client. That's now closed (ADR-0017, TD-SEC-026): consent is a real,
 * per-client {@code OAuthClient} attribute, defaulting to required, and this branch is
 * live-verified against an actually-rendered consent screen (see {@code
 * AuthorizationCodeFlowIntegrationTest}'s own consent-required test), not just unit-tested
 * path-matching. Stays correctly scoped for the day ADR-0009 replaces this page with a
 * project-owned, branded one (TD-SEC-011, still open).
 */
final class ContentSecurityPolicyHeaderWriter implements HeaderWriter {

  private static final String HEADER_NAME = "Content-Security-Policy";

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

  // Constructed only by each SecurityFilterChain builder's own `new
  // ContentSecurityPolicyHeaderWriter()` call — no state to initialize, same convention as this
  // package's other stateless writers/filters.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ ContentSecurityPolicyHeaderWriter() {
    // Intentionally empty.
  }

  @Override
  public void writeHeaders(final HttpServletRequest request, final HttpServletResponse response) {
    if (response.containsHeader(HEADER_NAME) || !isHtml(response)) {
      return;
    }
    response.setHeader(
        HEADER_NAME,
        CONSENT_PAGE_PATH.matcher(request.getRequestURI()).matches()
            ? CONSENT_PAGE_POLICY
            : STRICT_POLICY);
  }

  private static boolean isHtml(final HttpServletResponse response) {
    final String contentType = response.getContentType();
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
  }
}
