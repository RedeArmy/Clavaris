package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Where an unauthenticated request to {@code /oauth2/authorize} (or any other authenticated path on
 * {@link OrganizationAuthorizationServerConfig}'s chain) gets sent: the hosted login page for the
 * same Organization the request was already scoped to, {@code /o/{organizationId}/login}.
 *
 * <p>Parses {@code organizationId} straight off the current request's own path, not via {@link
 * CurrentOrganizationContext} — that class resolves the tenant from SAS's own issuer, which is only
 * reliably stripped of one of SAS's own registered endpoint suffixes; a plain path-segment read is
 * simpler and correct for every request this chain's {@code securityMatcher} can ever route here.
 * Same simple substring-scan style as {@code CurrentOrganizationContext}, deliberately, for
 * consistency.
 *
 * <p>{@code ExceptionTranslationFilter} (Spring Security core, not this class) already saves the
 * originally-requested URL via its own {@code RequestCache} before invoking this entry point — that
 * saved request is what lets {@link SpringSecurityAuthenticatedSessionEstablisher} send the browser
 * back to the real {@code /oauth2/authorize?...} URL once login succeeds, not something this class
 * needs to do itself.
 *
 * <p>ADR-0009 §1: forwards {@code client_id} (the OAuth2 spec's own param, translated onto the
 * login page's existing {@code clientId} query-param name — Clerk "customize redirect URLs" parity
 * already established that name, distinct from the spec's snake_case) and {@code display} (new,
 * additive — the iframe-modal signal) onto the login redirect. Without this, an unauthenticated
 * {@code /oauth2/authorize?...&display=modal} request would lose both by the time the browser ever
 * reaches {@code /o/{organizationId}/login} — the login page needs {@code clientId} to resolve
 * embedding eligibility and {@code display} to know it's being rendered inside an iframe at all.
 */
final class OrganizationLoginRedirectEntryPoint implements AuthenticationEntryPoint {

  private static final String PREFIX = "/o/";
  private static final String LOGIN_SUFFIX = "/login";
  private static final String DISPLAY_PARAM = "display";
  private static final String CLIENT_ID_PARAM = "clientId";

  // Constructed directly (new OrganizationLoginRedirectEntryPoint()) by
  // OrganizationAuthorizationServerConfig, not Spring's own component scan — this class holds no
  // state, so there's nothing beyond the implicit default for it to do.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationLoginRedirectEntryPoint() {
    // Intentionally empty.
  }

  @Override
  public void commence(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException authException)
      throws IOException {
    final String path = request.getRequestURI();
    final int prefixIndex = path.indexOf(PREFIX);
    if (prefixIndex < 0) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    final String afterPrefix = path.substring(prefixIndex + PREFIX.length());
    final int nextSlash = afterPrefix.indexOf('/');
    final String organizationId = nextSlash < 0 ? afterPrefix : afterPrefix.substring(0, nextSlash);
    final String loginUrl = request.getContextPath() + PREFIX + organizationId + LOGIN_SUFFIX;
    response.sendRedirect(
        appendIfPresent(
            appendIfPresent(
                loginUrl, CLIENT_ID_PARAM, request.getParameter(OAuth2ParameterNames.CLIENT_ID)),
            DISPLAY_PARAM,
            request.getParameter(DISPLAY_PARAM)));
  }

  // Two genuinely distinct outcomes (nothing to append / append one param) — same "each outcome
  // needs its own exit" rationale as identity-module's own RedirectQueryParams#appendIfPresent,
  // duplicated here rather than shared across the module boundary for a two-line helper.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String appendIfPresent(
      final String baseUrl, final String paramName, final String value) {
    if (value == null) {
      return baseUrl;
    }
    final String separator = baseUrl.indexOf('?') >= 0 ? "&" : "?";
    return baseUrl + separator + paramName + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
