package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-0010 §6.2/BR-ORG-05: the capacity layer — a per-{@code Organization} aggregate ceiling
 * combining every request this filter sees for that Organization (login + token together, one
 * counter, matching "aggregate" in the ADR's own wording — not one independent ceiling per
 * endpoint), distinct from {@link AntiAbuseRateLimitingFilter}'s own fixed, per-identifier layer.
 * Registered only on {@code OrganizationAuthorizationServerConfig}'s chain — the platform tier has
 * no {@code organization_id} to aggregate by at all, so this layer structurally doesn't apply
 * there.
 *
 * <p>TD-FUT-012 (closed 2026-09-02): reads {@link RateLimitPolicyRepository} on every matched
 * request, same as before — but the bean actually injected here is now {@link
 * CachingRateLimitPolicyRepository}, a short-TTL, write-through in-memory cache in front of the
 * real Postgres read, not this filter's own concern. This filter's code is unchanged by that fix on
 * purpose: it depends only on the port, exactly as hexagonal architecture intends, so the caching
 * decision could be made entirely at the composition-root/adapter level with zero changes here.
 */
@SuppressWarnings("PMD.LongVariable")
final class OrganizationCapacityRateLimitingFilter extends OncePerRequestFilter {

  // Matches /o/{organizationId}/... — the same path shape every rule this filter's own chain
  // (OrganizationAuthorizationServerConfig) protects already requires.
  private static final Pattern ORGANIZATION_ID_PATH_SEGMENT = Pattern.compile("^/o/([^/]+)/.*");

  private final RateLimiter rateLimiter;
  private final RateLimitPolicyRepository policies;
  private final int systemDefaultRequestsPerMinute;

  /* package */ OrganizationCapacityRateLimitingFilter(
      final RateLimiter rateLimiter,
      final RateLimitPolicyRepository policies,
      final int systemDefaultRequestsPerMinute) {
    super();
    this.rateLimiter = rateLimiter;
    this.policies = policies;
    this.systemDefaultRequestsPerMinute = systemDefaultRequestsPerMinute;
  }

  // "Nothing to aggregate"/"under the ceiling"/"over the ceiling" are three independent, equally
  // valid exits — same rationale as RegisterAccountController's own suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final UUID organizationId = extractOrganizationId(request);
    if (organizationId == null) {
      // Not one of the /o/{organizationId}/... paths this layer aggregates — nothing to do
      // (defensive: securityMatcher already scopes this chain, but this filter makes no
      // assumption about what else might one day share it).
      filterChain.doFilter(request, response);
      return;
    }

    final int limit =
        policies
            .findByOrganizationId(organizationId)
            .map(RateLimitPolicy::requestsPerMinute)
            .orElse(systemDefaultRequestsPerMinute);
    final RateLimitDecision decision =
        rateLimiter.tryConsume(
            "ratelimit:capacity:" + organizationId, limit, Duration.ofMinutes(1));

    if (!decision.allowed()) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setHeader(
          "Retry-After", String.valueOf(Math.max(1, decision.retryAfter().toSeconds())));
      response.setContentType("text/plain;charset=UTF-8");
      response.getWriter().write("Too many requests. Please try again later.");
      return;
    }
    filterChain.doFilter(request, response);
  }

  // "No path match"/"malformed id"/"resolved" are three independent, equally valid exits — same
  // rationale as CurrentPlatformAccountResolverBridge's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static UUID extractOrganizationId(final HttpServletRequest request) {
    final Matcher matcher = ORGANIZATION_ID_PATH_SEGMENT.matcher(request.getRequestURI());
    if (!matcher.matches()) {
      return null;
    }
    try {
      return UUID.fromString(matcher.group(1));
    } catch (final IllegalArgumentException _) {
      // A malformed organizationId segment can't be aggregated against a real Organization's own
      // policy — same "malformed input surfaces as absent, never an exception" convention as
      // CurrentOrganizationContext's own empty-Optional paths. The request still proceeds to
      // whatever downstream handling a bad id gets (a 404-shaped rejection further down the
      // chain), just without this layer's own aggregate counter applying to it.
      return null;
    }
  }
}
