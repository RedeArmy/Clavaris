package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * ADR-0010 §6.1: unit-level coverage of the rule-matching/multi-rule/blocking logic itself, against
 * a mocked {@link RateLimiter} — {@link RedisFixedWindowRateLimiterTest} already covers the real
 * Redis mechanics; this class is about proving THIS filter evaluates rules correctly, not
 * re-proving Redis atomicity.
 */
class AntiAbuseRateLimitingFilterTest {

  // A fixed test-only secret, matching every other RateLimitKeyHasher instantiation in this test
  // class — the exact key value doesn't matter here (RateLimitKeyHasherTest already covers keying
  // correctness in isolation), only that every rateLimiter.tryConsume(...) expectation below is
  // computed against this same instance.
  private static final RateLimitKeyHasher KEY_HASHER = new RateLimitKeyHasher("test-secret");

  @Test
  void allowsARequestThatMatchesNoRuleWithoutConsultingTheRateLimiterAtAll() throws Exception {
    RateLimiter rateLimiter = mock(RateLimiter.class);
    AntiAbuseRateLimitingFilter filter =
        new AntiAbuseRateLimitingFilter(
            rateLimiter,
            KEY_HASHER,
            List.of(
                new RateLimitRule(
                    "login:account",
                    HttpMethod.POST,
                    "/o/*/login",
                    RateLimitRule.always(),
                    RateLimitIdentifiers::emailFormField,
                    10,
                    Duration.ofMinutes(5))));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/some-org/register");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).as("an unmatched request must reach the real chain").isNotNull();
    verify(rateLimiter, never()).tryConsume(any(), anyInt(), any());
  }

  @Test
  void allowsARequestUnderTheLimitAndPassesItThrough() throws Exception {
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryConsume(any(), eq(10), any()))
        .thenReturn(new RateLimitDecision(true, 3, Duration.ofMinutes(5)));
    AntiAbuseRateLimitingFilter filter =
        new AntiAbuseRateLimitingFilter(
            rateLimiter,
            KEY_HASHER,
            List.of(
                new RateLimitRule(
                    "login:account",
                    HttpMethod.POST,
                    "/o/*/login",
                    RateLimitRule.always(),
                    RateLimitIdentifiers::emailFormField,
                    10,
                    Duration.ofMinutes(5))));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/o/some-org/login");
    request.setParameter("email", "user@example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void blocksWith429AndARetryAfterHeaderWhenTheLimitIsExceeded() throws Exception {
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryConsume(any(), eq(10), any()))
        .thenReturn(new RateLimitDecision(false, 11, Duration.ofSeconds(42)));
    AntiAbuseRateLimitingFilter filter =
        new AntiAbuseRateLimitingFilter(
            rateLimiter,
            KEY_HASHER,
            List.of(
                new RateLimitRule(
                    "login:account",
                    HttpMethod.POST,
                    "/o/*/login",
                    RateLimitRule.always(),
                    RateLimitIdentifiers::emailFormField,
                    10,
                    Duration.ofMinutes(5))));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/o/some-org/login");
    request.setParameter("email", "user@example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest())
        .as("a blocked request must never reach the real chain — not even a dry-run pass-through")
        .isNull();
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("42");
  }

  @Test
  void skipsARuleWhenItsOwnKeyExtractorFindsNothingToKeyOn() throws Exception {
    RateLimiter rateLimiter = mock(RateLimiter.class);
    AntiAbuseRateLimitingFilter filter =
        new AntiAbuseRateLimitingFilter(
            rateLimiter,
            KEY_HASHER,
            List.of(
                new RateLimitRule(
                    "login:account",
                    HttpMethod.POST,
                    "/o/*/login",
                    RateLimitRule.always(),
                    RateLimitIdentifiers::emailFormField,
                    10,
                    Duration.ofMinutes(5))));
    // No "email" parameter at all — a malformed/incomplete POST this rule has nothing to key on.
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/o/some-org/login");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    verify(rateLimiter, never()).tryConsume(any(), anyInt(), any());
  }

  @Test
  void evaluatesEveryMatchingRuleIndependentlyAndBlocksIfAnyOneOfThemIsExceeded() throws Exception {
    // BR-ID-06's own two-layer split (per-account, per-IP) only works if both rules actually run
    // against the same request — this proves the per-IP rule alone can block even though the
    // per-account rule for the same request would have allowed it.
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryConsume(
            eq("ratelimit:login:account:" + KEY_HASHER.hash("user@example.com")), eq(10), any()))
        .thenReturn(new RateLimitDecision(true, 1, Duration.ofMinutes(5)));
    when(rateLimiter.tryConsume(
            eq("ratelimit:login:ip:" + KEY_HASHER.hash("127.0.0.1")), eq(30), any()))
        .thenReturn(new RateLimitDecision(false, 31, Duration.ofSeconds(17)));
    AntiAbuseRateLimitingFilter filter =
        new AntiAbuseRateLimitingFilter(
            rateLimiter,
            KEY_HASHER,
            List.of(
                new RateLimitRule(
                    "login:account",
                    HttpMethod.POST,
                    "/o/*/login",
                    RateLimitRule.always(),
                    RateLimitIdentifiers::emailFormField,
                    10,
                    Duration.ofMinutes(5)),
                new RateLimitRule(
                    "login:ip",
                    HttpMethod.POST,
                    "/o/*/login",
                    RateLimitRule.always(),
                    RateLimitIdentifiers::sourceIp,
                    30,
                    Duration.ofMinutes(5))));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/o/some-org/login");
    request.setParameter("email", "user@example.com");
    request.setRemoteAddr("127.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("17");
  }

  @Test
  void neverLeaksWhichSpecificRuleWasHitInTheResponseBody() throws Exception {
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryConsume(any(), anyInt(), any()))
        .thenReturn(new RateLimitDecision(false, 99, Duration.ofSeconds(5)));
    AntiAbuseRateLimitingFilter filter =
        new AntiAbuseRateLimitingFilter(
            rateLimiter,
            KEY_HASHER,
            List.of(
                new RateLimitRule(
                    "login:account",
                    HttpMethod.POST,
                    "/o/*/login",
                    RateLimitRule.always(),
                    RateLimitIdentifiers::emailFormField,
                    10,
                    Duration.ofMinutes(5))));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/o/some-org/login");
    request.setParameter("email", "user@example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getContentAsString())
        .doesNotContain("login:account", "user@example.com")
        .contains("Too many requests");
  }
}
