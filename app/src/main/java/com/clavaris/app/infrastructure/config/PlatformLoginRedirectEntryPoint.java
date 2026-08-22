package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Where an unauthenticated request to {@code /platform/dashboard} (or any other authenticated path
 * on {@link PlatformDashboardSecurityConfig}'s chain) gets sent — simpler than {@link
 * OrganizationLoginRedirectEntryPoint}, since there's no {@code organizationId} to parse: every
 * {@code PlatformAccount} shares the one {@code /platform/login} page.
 */
final class PlatformLoginRedirectEntryPoint implements AuthenticationEntryPoint {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ PlatformLoginRedirectEntryPoint() {
    // Intentionally empty.
  }

  @Override
  public void commence(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException authException)
      throws IOException {
    response.sendRedirect(request.getContextPath() + "/platform/login");
  }
}
