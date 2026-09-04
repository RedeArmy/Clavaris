package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-0023 §3: the enforcement half of the {@code organization_id} claim {@link
 * OrganizationClientClaimCustomizer} stamps onto an {@code OrganizationClient}'s own minted token —
 * no {@code organization_id} claim (a {@code PlatformClient} token) passes through unchanged,
 * today's behaviour, zero regression risk. A claim present is resolved against a small, explicit
 * {@code routes} allowlist — **no match found is a 403, not a silent pass** (fail-closed, same
 * posture BR-ID-12 already establishes elsewhere): this is a real allowlist, not a blocklist, so an
 * endpoint this class was never told about is unreachable by an {@code OrganizationClient} token by
 * construction, not by remembering to add a check to it. A match found but the resolved
 * Organization doesn't equal the claim is also a 403 — the exact cross-tenant attempt this whole
 * feature exists to prevent.
 *
 * <p>No existing controller is touched to add this check piecemeal — same "one filter, not N
 * retrofits" reasoning {@code AntiAbuseRateLimitingFilter}'s own {@code List<RateLimitRule>} design
 * already establishes for an analogous "one cross-cutting concern, many routes" shape. Two resolver
 * shapes: **direct** ({@code organizationId} is itself a path variable) and **one-hop** ({@code
 * accountId}/{@code workspaceId} resolved to its owning Organization via {@link
 * AccountRepository#findOrganizationIdById}/{@link WorkspaceRepository#findOrganizationIdById} —
 * both already existed for other callers, no new lookup port needed).
 *
 * <p>Anchored after {@code AntiAbuseRateLimitingFilter} in {@code AdminApiSecurityConfig} — needs
 * the authenticated {@code JwtAuthenticationToken} already in the security context, same reason
 * that filter is itself anchored after {@code BearerTokenAuthenticationFilter}.
 *
 * <p>{@code PMD.OnlyOneReturn}: several methods here have genuinely distinct early-exit branches
 * (no claim, no route match, malformed id) — same rationale {@code SocialLoginRedirectController}'s
 * own identical suppression already documents for this exact class of guard-clause-heavy resolution
 * logic. {@code PMD.LongVariable}: {@code tokenOrganizationId}/{@code targetOrganizationId}/{@code
 * organizationIdResolver} name exactly what they are.
 */
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LongVariable"})
final class OrganizationClientOwnershipFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  /**
   * v1 OrganizationClient-reachable set, matching Clerk's own stated Secret-Key powers, named here
   * explicitly rather than opened broadly — see ADR-0023 §3 for the full rationale on what's
   * deliberately excluded (org lifecycle itself, account delete/suspend/reactivate).
   */
  private final List<Route> routes;

  private final AccountRepository accounts;
  private final WorkspaceRepository workspaces;

  /* package */ OrganizationClientOwnershipFilter(
      final AccountRepository accounts, final WorkspaceRepository workspaces) {
    super();
    this.accounts = accounts;
    this.workspaces = workspaces;
    this.routes =
        List.of(
            direct(HttpMethod.GET, "/api/v1/admin/organizations/{organizationId}/api-keys"),
            direct(HttpMethod.GET, "/api/v1/admin/organizations/{organizationId}/secret-keys"),
            direct(HttpMethod.POST, "/api/v1/admin/organizations/{organizationId}/secret-keys"),
            direct(
                HttpMethod.PUT, "/api/v1/admin/organizations/{organizationId}/rate-limit-policy"),
            direct(
                HttpMethod.PUT, "/api/v1/admin/organizations/{organizationId}/social-login-policy"),
            direct(
                HttpMethod.GET, "/api/v1/admin/organizations/{organizationId}/social-credentials"),
            direct(
                HttpMethod.PUT,
                "/api/v1/admin/organizations/{organizationId}/social-credentials/{provider}"),
            direct(
                HttpMethod.DELETE,
                "/api/v1/admin/organizations/{organizationId}/social-credentials/{provider}"),
            direct(
                HttpMethod.POST,
                "/api/v1/admin/organizations/{organizationId}/signing-keys/rotate"),
            direct(
                HttpMethod.POST,
                "/api/v1/admin/organizations/{organizationId}/signing-keys/{kid}:purge"),
            direct(HttpMethod.GET, "/api/v1/admin/organizations/{organizationId}/workspaces"),
            direct(HttpMethod.POST, "/api/v1/admin/organizations/{organizationId}/workspaces"),
            direct(HttpMethod.POST, "/api/v1/admin/organizations/{organizationId}/clients"),
            direct(
                HttpMethod.GET, "/api/v1/admin/organizations/{organizationId}/webhook-endpoints"),
            direct(
                HttpMethod.POST, "/api/v1/admin/organizations/{organizationId}/webhook-endpoints"),
            oneHopAccount(HttpMethod.POST, "/api/v1/admin/accounts/{accountId}:impersonate"),
            oneHopWorkspace(HttpMethod.POST, "/api/v1/admin/workspaces/{workspaceId}/members"),
            oneHopWorkspace(
                HttpMethod.PUT, "/api/v1/admin/workspaces/{workspaceId}/members/{accountId}/role"),
            oneHopWorkspace(
                HttpMethod.POST,
                "/api/v1/admin/workspaces/{workspaceId}/members/{accountId}:remove"));
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final Optional<UUID> tokenOrganizationId = organizationIdClaim();
    if (tokenOrganizationId.isEmpty()) {
      // No claim at all — a PlatformClient token, unscoped by design. Today's behaviour, unchanged.
      filterChain.doFilter(request, response);
      return;
    }

    final Optional<UUID> targetOrganizationId = resolveTarget(request);
    if (targetOrganizationId.isEmpty() || !targetOrganizationId.equals(tokenOrganizationId)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private Optional<UUID> organizationIdClaim() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
      return Optional.empty();
    }
    final String claim =
        jwtAuthentication
            .getToken()
            .getClaimAsString(OrganizationClientClaimCustomizer.ORGANIZATION_ID_CLAIM);
    if (claim == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(claim));
    } catch (final IllegalArgumentException _) {
      // Malformed claim should never happen (only OrganizationClientClaimCustomizer ever writes
      // it, always a real UUID's own toString()) — fail closed, same posture as everywhere else in
      // this method.
      return Optional.empty();
    }
  }

  private Optional<UUID> resolveTarget(final HttpServletRequest request) {
    final String path = request.getRequestURI();
    for (final Route route : routes) {
      if (route.method().matches(request.getMethod())
          && PATH_MATCHER.match(route.pattern(), path)) {
        final Map<String, String> variables =
            PATH_MATCHER.extractUriTemplateVariables(route.pattern(), path);
        return route.organizationIdResolver().apply(variables);
      }
    }
    return Optional.empty();
  }

  private static Route direct(final HttpMethod method, final String pattern) {
    return new Route(method, pattern, variables -> parseUuid(variables.get("organizationId")));
  }

  private Route oneHopAccount(final HttpMethod method, final String pattern) {
    return new Route(
        method,
        pattern,
        variables ->
            parseUuid(variables.get("accountId"))
                .flatMap(accountId -> accounts.findOrganizationIdById(new AccountId(accountId)))
                .map(OrganizationId::value));
  }

  private Route oneHopWorkspace(final HttpMethod method, final String pattern) {
    return new Route(
        method,
        pattern,
        variables ->
            parseUuid(variables.get("workspaceId")).flatMap(workspaces::findOrganizationIdById));
  }

  private static Optional<UUID> parseUuid(final String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private record Route(
      HttpMethod method,
      String pattern,
      Function<Map<String, String>, Optional<UUID>> organizationIdResolver) {}
}
