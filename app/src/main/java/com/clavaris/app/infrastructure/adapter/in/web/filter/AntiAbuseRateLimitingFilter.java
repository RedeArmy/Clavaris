package com.clavaris.app.infrastructure.adapter.in.web.filter;

import com.clavaris.app.infrastructure.adapter.out.security.RateLimitKeyHasher;
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
// PMD.LongVariable: TOO_MANY_REQUESTS_MESSAGE/TOO_MANY_REQUESTS_HTML each name exactly what they
// hold — same "descriptive over short" rationale identity-module's own IdentityUseCaseConfig
// class-level suppression already documents for this project.
@SuppressWarnings("PMD.LongVariable")
public final class AntiAbuseRateLimitingFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  // BR-DATA-01/anti-enumeration: the one message every blocked request gets, regardless of which
  // rule/endpoint actually tripped — see respondTooManyRequests's own comment for the two
  // representations (plain text for an API caller, this same text inside a styled page for a
  // browser) this backs.
  private static final String TOO_MANY_REQUESTS_MESSAGE =
      "Too many requests. Please try again later.";

  // Standalone (no Thymeleaf/MVC context available inside a raw Servlet filter) but reuses
  // clavaris.css's own class names — see respondTooManyRequests's own comment for why that link
  // resolves correctly regardless of which handler served this particular response.
  private static final String TOO_MANY_REQUESTS_HTML =
      "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"/>"
          + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
          + "<title>Too many requests — Clavaris</title>"
          + "<link rel=\"stylesheet\" href=\"/css/clavaris.css\"/></head><body><main>"
          + "<div class=\"clavaris-card\"><h1>Too many requests</h1>"
          + "<p class=\"clavaris-alert clavaris-alert--error\">"
          + TOO_MANY_REQUESTS_MESSAGE
          + "</p></div></main></body></html>";

  private final RateLimiter rateLimiter;
  private final RateLimitKeyHasher keyHasher;
  private final List<RateLimitRule> rules;

  public AntiAbuseRateLimitingFilter(
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
      respondTooManyRequests(request, response, longestRetryAfter);
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

  // SDE-III review, 2026-09-16: this filter also protects /o/*/login (see this class's own
  // Javadoc "Runs every matching rule" note referencing BR-ID-06) — a real browser hitting it mid
  // sign-in used to get a bare, unstyled text/plain body, jarring next to every other page on this
  // same origin (login.html's own serviceOverloadedError alert already establishes the "distinct,
  // on-brand message, never a raw error" bar for the structurally identical TD-FUT-017 concurrency
  // gate). A raw Servlet filter has no Thymeleaf/MVC context to render through, but it doesn't need
  // one: /css/clavaris.css is a static resource the browser fetches independently regardless of
  // which handler served the HTML that references it, so a small standalone page reusing the same
  // class names renders identically to every MVC-rendered page. Content-negotiated on the request's
  // own Accept header, not the response's path — a browser navigation sends "text/html" there; an
  // API/fetch client (including /oauth2/token, this filter's other real caller) typically doesn't,
  // and keeps the original plain-text body unchanged.
  //
  // PMD.LawOfDemeter: response.getWriter() is the standard Servlet API shape for writing a body
  // directly from a filter — there is no other way to reach it.
  @SuppressWarnings("PMD.LawOfDemeter")
  private void respondTooManyRequests(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Duration retryAfter)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter.toSeconds())));

    final String accept = request.getHeader("Accept");
    if (accept != null && accept.contains("text/html")) {
      response.setContentType("text/html;charset=UTF-8");
      response.getWriter().write(TOO_MANY_REQUESTS_HTML);
      return;
    }
    response.setContentType("text/plain;charset=UTF-8");
    // BR-DATA-01/anti-enumeration: identical for every rule this could have been — never reveals
    // which specific limit (account vs. IP, or which endpoint) was hit.
    response.getWriter().write(TOO_MANY_REQUESTS_MESSAGE);
  }
}
