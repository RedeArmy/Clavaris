package com.clavaris.app.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security finding (SDE-III review, 2026-08-22), mirror of the {@code
 * PlatformDashboardSecurityConfig} fix on this chain: {@code
 * OrganizationAuthorizationServerConfig}'s {@code
 * organizationAuthorizationServerSecurityFilterChain} shares the one app-wide {@code
 * SecurityContextRepository} bean with the platform tier, and its {@code
 * .authorizeHttpRequests().anyRequest().authenticated()} check turned out to have no effect at all
 * on {@code /oauth2/authorize} — decompiling {@code
 * OAuth2AuthorizationCodeRequestAuthenticationConverter} confirmed it reads {@code
 * SecurityContextHolder.getContext().getAuthentication()} directly, inside {@code
 * OAuth2AuthorizationEndpointFilter}, which fully handles and commits the response before {@code
 * AuthorizationFilter} (the filter {@code authorizeHttpRequests} actually installs) ever runs.
 * {@code hasAuthority(...)} in {@code authorizeHttpRequests}, the fix used for the dashboard, is
 * therefore not reachable here — the gate has to sit earlier in the chain, before any of Spring
 * Authorization Server's own filters read {@link SecurityContextHolder}.
 *
 * <p>What this filter does: if the request already carries an authenticated session lacking {@code
 * ROLE_ACCOUNT} (a {@code PlatformAccount} session, most concretely — see {@code
 * SpringSecurityPlatformAuthenticatedSessionEstablisher}'s own {@code ROLE_PLATFORM_ACCOUNT}), it
 * resets {@link SecurityContextHolder} to an empty context for the rest of this request only —
 * nothing is written back to the session store, so the PlatformAccount's own real session is
 * untouched and still works normally against {@code /platform/dashboard}. Spring Authorization
 * Server's own {@code OAuth2AuthorizationCodeRequestAuthenticationConverter} already falls back to
 * constructing a fresh {@code AnonymousAuthenticationToken} whenever it finds no {@code
 * Authentication} in the context (confirmed by decompilation, not assumed) — an empty context here
 * is therefore treated exactly like a genuinely-unauthenticated visitor, and gets the same {@code
 * /o/{organizationId}/login} redirect via {@code OrganizationLoginRedirectEntryPoint} that already
 * exists for that case, not a new code path.
 *
 * <p>Registered via {@code addFilterAfter(this, SecurityContextHolderFilter.class)} — as early as
 * this chain allows, immediately after the context is loaded from the repository and before every
 * other filter on this chain (CSRF, session management, every Spring Authorization Server endpoint
 * filter) gets a chance to read it.
 */
final class TenantAccountOnlySecurityContextFilter extends OncePerRequestFilter {

  // The exact authority SpringSecurityAuthenticatedSessionEstablisher (tenant tier) grants and no
  // PlatformAccount session ever carries.
  @SuppressWarnings("PMD.LongVariable")
  private static final String TENANT_ACCOUNT_AUTHORITY = "ROLE_ACCOUNT";

  // This class holds no state, so this constructor is otherwise a no-op — written out explicitly
  // for the same reason as e.g. PlatformDashboardSecurityConfig's own identical empty constructor.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ TenantAccountOnlySecurityContextFilter() {
    super();
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !isTenantAccount(authentication)) {
      SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
    }
    filterChain.doFilter(request, response);
  }

  private static boolean isTenantAccount(final Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(TENANT_ACCOUNT_AUTHORITY::equals);
  }
}
