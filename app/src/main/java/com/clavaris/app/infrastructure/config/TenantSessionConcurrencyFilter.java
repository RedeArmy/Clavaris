package com.clavaris.app.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TD-SEC-031 (SDE-III review, 2026-08-26) — wraps a real {@link ConcurrentSessionFilter} (ADR-0003:
 * build on the framework, never reimplement it), not a bespoke expiry check, but skips it entirely
 * for this chain's own machine-authenticated SAS endpoints.
 *
 * <p><b>Real bug, caught live by {@code RefreshTokenRotationIntegrationTest} before this fix
 * existed:</b> a plain {@code sessionConcurrency()} wiring (this class's own first version) broke a
 * legitimate {@code POST /oauth2/token} refresh-token exchange the moment the SAME browser's
 * unrelated, already-expired {@code Account} session happened to still be riding along as a cookie.
 * Confirmed by tracing the real Spring Security filter order on this chain (TRACE logging, not
 * guessed): {@code OAuth2TokenEndpointFilter} sits *after* {@code AuthorizationFilter} in this
 * chain's own actual filter list, so {@code .anyRequest().authenticated()} — despite this class's
 * own Javadoc claiming SAS's filters "fully handle/commit... before AuthorizationFilter... ever
 * runs" — genuinely does gate every SAS endpoint here, including ones (like {@code /oauth2/token})
 * whose real authentication is entirely independent of the browser's own session (Basic-Auth client
 * credentials, not cookies). {@code ConcurrentSessionFilter}'s own {@code doLogout()} step
 * unconditionally clears {@code SecurityContextHolder} the moment it finds an expired session — for
 * a machine caller that never depended on that session's authentication in the first place, that
 * clearing is what made {@code AuthorizationFilter} reject an otherwise-perfectly-valid,
 * correctly-Basic-Auth'd token request with a login-page redirect instead of ever letting it reach
 * {@code OAuth2TokenEndpointFilter}.
 *
 * <p>{@code /oauth2/authorize} is deliberately NOT exempted — a stale session hitting the
 * interactive endpoint ending up redirected to login (whether via {@code AuthorizationFilter}'s own
 * generic gate or SAS's own internal check) is exactly the correct, desired TD-SEC-031 outcome, not
 * a bug to route around. Same exempt-path set as this chain's own CSRF ignore list (SAS's {@code
 * endpointsMatcher}), minus {@code /oauth2/authorize} — every one of these is either
 * unauthenticated by design ({@code /oauth2/jwks}, {@code /.well-known/**}) or authenticates itself
 * independently of any {@code HttpSession} (Basic-Auth client credentials for {@code
 * /oauth2/token}/{@code introspect}/{@code revoke}; Bearer token for {@code /userinfo}).
 */
final class TenantSessionConcurrencyFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  @SuppressWarnings("PMD.LongVariable")
  private static final List<String> MACHINE_ENDPOINT_PATTERNS =
      List.of(
          "/o/*/oauth2/token",
          "/o/*/oauth2/introspect",
          "/o/*/oauth2/revoke",
          "/o/*/oauth2/device_authorization",
          "/o/*/oauth2/par",
          "/o/*/oauth2/jwks",
          "/o/*/.well-known/**",
          "/o/*/userinfo");

  private final ConcurrentSessionFilter delegate;

  /* package */ TenantSessionConcurrencyFilter(final SessionRegistry sessionRegistry) {
    super();
    // maximumSessions is unlimited (ADR-0010/TD-SEC-031's own reasoning: this exists for
    // revocation, not to cap concurrent devices) — nothing here ever calls the constructor
    // overload that would enforce a login-time cap, so no ConcurrentSessionControlAuthentication
    // Strategy needs wiring on the authentication side, only this request-time expiry check.
    this.delegate =
        new ConcurrentSessionFilter(
            sessionRegistry, new InvalidateAndContinueSessionExpiredStrategy());
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    if (isMachineEndpoint(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    delegate.doFilter(request, response, filterChain);
  }

  private static boolean isMachineEndpoint(final HttpServletRequest request) {
    final String uri = request.getRequestURI();
    return MACHINE_ENDPOINT_PATTERNS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, uri));
  }
}
