package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
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
 */
final class OrganizationLoginRedirectEntryPoint implements AuthenticationEntryPoint {

  private static final String PREFIX = "/o/";
  private static final String LOGIN_SUFFIX = "/login";

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
    response.sendRedirect(request.getContextPath() + PREFIX + organizationId + LOGIN_SUFFIX);
  }
}
