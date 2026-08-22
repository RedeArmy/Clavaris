package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.http.HttpMethod;

/**
 * ADR-0010 §6.1: one independent anti-abuse counter — {@link AntiAbuseRateLimitingFilter} evaluates
 * every rule whose {@code method}/{@code pathPattern}/{@code extraCondition} match a request, not
 * just the first one, since the whole point of splitting "per-account" from "per-IP" (BR-ID-06) is
 * that both apply to the same login attempt independently.
 *
 * @param name identifies this rule's own Redis key namespace — never derived from user input, so
 *     it's safe to interpolate directly (unlike {@code keyExtractor}'s own output, which is hashed
 *     before use, see {@link AntiAbuseRateLimitingFilter})
 * @param method the HTTP method this rule applies to
 * @param pathPattern an {@code AntPathMatcher} pattern, e.g. {@code "/o/*&#47;login"}
 * @param extraCondition an additional per-request check beyond method/path — e.g. "grant_type is
 *     not refresh_token" for the token endpoint's non-refresh rule (BR-ID-06: refresh cycles must
 *     never be throttled by the same threshold as raw credential submission). {@code r -> true} for
 *     rules that need no further narrowing.
 * @param keyExtractor resolves the identifier this rule counts by (an email, an IP, a client_id, a
 *     platform account id) from the request. Returning {@code null} means "this request has nothing
 *     to key on" — the rule is skipped for that request rather than counted against a null/empty
 *     key shared by every such request.
 * @param limit attempts allowed per {@code window}
 * @param window the fixed-window duration
 */
record RateLimitRule(
    String name,
    HttpMethod method,
    String pathPattern,
    Predicate<HttpServletRequest> extraCondition,
    Function<HttpServletRequest, String> keyExtractor,
    int limit,
    Duration window) {

  /* package */ static Predicate<HttpServletRequest> always() {
    return request -> true;
  }
}
