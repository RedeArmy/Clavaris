package com.clavaris.app.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-0010 §6.1/BR-ID-06/BR-ORG-05: the anti-abuse layer — fixed, system-defined thresholds, never
 * tenant-configurable (no {@code RateLimitPolicy} row governs this; the constant {@link
 * RateLimitRule} list each security chain wires in is the entire configuration surface). One
 * instance of this filter is registered per relevant chain ({@code
 * OrganizationAuthorizationServerConfig}, {@code PlatformAuthorizationServerConfig}, {@code
 * PlatformDashboardSecurityConfig}), each with its own {@link RateLimitRule} list scoped to that
 * chain's own endpoints — see each config's own wiring for why a given rule's limit/window was
 * chosen.
 *
 * <p>Runs every matching rule for a request, not just the first — BR-ID-06's own two-layer split
 * (per-account, per-IP) only works if both count independently against the same login attempt.
 * Blocks with the longest {@code Retry-After} among every rule that was actually exceeded, so a
 * client that hit two different limits at once waits for the one that actually clears last.
 *
 * <p>Identifiers ({@code keyExtractor}'s own output — an email, an IP, a client_id) are HMAC-SHA256
 * hashed (TD-SEC-023, {@link RateLimitKeyHasher}) before becoming part of a Redis key, never stored
 * raw — Redis is a secondary store this project's own data-protection review doesn't cover the same
 * way the primary Postgres schema does (`data-model.md` §2's hash-only convention for every other
 * secondary artifact), and an email address is PII regardless of whether it's also a bearer secret.
 * Keyed, not plain SHA-256: a plain digest of a known-format, low-entropy value like an email or an
 * IPv4 is reversible via an offline dictionary attack by anyone who can read the Redis keyspace —
 * see {@link RateLimitKeyHasher}'s own Javadoc for the full reasoning, including why this uses a
 * dedicated secret rather than reusing {@code BearerTokenHasher}'s.
 */
final class AntiAbuseRateLimitingFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private final RateLimiter rateLimiter;
  private final RateLimitKeyHasher keyHasher;
  private final List<RateLimitRule> rules;

  /* package */ AntiAbuseRateLimitingFilter(
      final RateLimiter rateLimiter,
      final RateLimitKeyHasher keyHasher,
      final List<RateLimitRule> rules) {
    super();
    this.rateLimiter = rateLimiter;
    this.keyHasher = keyHasher;
    this.rules = List.copyOf(rules);
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    Duration longestRetryAfter = null;

    for (final RateLimitRule rule : rules) {
      final Duration retryAfter = evaluateRule(rule, request);
      if (retryAfter != null
          && (longestRetryAfter == null || retryAfter.compareTo(longestRetryAfter) > 0)) {
        longestRetryAfter = retryAfter;
      }
    }

    if (longestRetryAfter != null) {
      respondTooManyRequests(response, longestRetryAfter);
      return;
    }
    filterChain.doFilter(request, response);
  }

  // Returns null when the rule doesn't apply/has nothing to key on/wasn't exceeded — a single
  // return-based skip in one place, not scattered continue statements through the caller's loop
  // (the SonarCloud finding this method exists to fix). PMD.OnlyOneReturn suppressed: same
  // present/absent multi-exit shape RateLimitIdentifiers' own class-wide suppression already
  // covers, not an organically grown method that should be restructured.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private Duration evaluateRule(final RateLimitRule rule, final HttpServletRequest request) {
    if (!matches(rule, request)) {
      return null;
    }
    final String identifier = rule.keyExtractor().apply(request);
    if (identifier == null) {
      // Nothing to key this specific request on for this rule (e.g. no client_id presented at
      // all) — skip it rather than count every such request against one shared "unknown" key,
      // which would let one malformed-request flood exhaust a counter real requests also rely on.
      return null;
    }
    final String redisKey = "ratelimit:" + rule.name() + ":" + keyHasher.hash(identifier);
    final RateLimitDecision decision =
        rateLimiter.tryConsume(redisKey, rule.limit(), rule.window());
    return decision.allowed() ? null : decision.retryAfter();
  }

  private boolean matches(final RateLimitRule rule, final HttpServletRequest request) {
    return rule.method().matches(request.getMethod())
        && PATH_MATCHER.match(rule.pathPattern(), request.getRequestURI())
        && rule.extraCondition().test(request);
  }

  // PMD.LawOfDemeter: response.getWriter() is the standard Servlet API shape for writing a body
  // directly from a filter — there is no other way to reach it.
  @SuppressWarnings("PMD.LawOfDemeter")
  private void respondTooManyRequests(final HttpServletResponse response, final Duration retryAfter)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter.toSeconds())));
    response.setContentType("text/plain;charset=UTF-8");
    // BR-DATA-01/anti-enumeration: identical for every rule this could have been — never reveals
    // which specific limit (account vs. IP, or which endpoint) was hit.
    response.getWriter().write("Too many requests. Please try again later.");
  }
}
