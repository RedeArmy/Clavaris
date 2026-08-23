package com.clavaris.app.infrastructure.config;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * ADR-0010 §6 (rate limiting is mandatory from day one, ADR-0004: Redis for exactly this). One
 * Redis key per {@code (identifier, window)} pair, {@code INCR}-ed on every attempt; the key's
 * first increment in a fresh window also sets its own expiry, so an idle key disappears on its own
 * — no separate cleanup job, no unbounded key growth.
 *
 * <p>The increment-then-conditionally-expire sequence runs as one Lua script, not two separate
 * {@code INCR}/{@code EXPIRE} calls from this class — Redis executes a script atomically against
 * every other client, so there is no window where a key could be incremented by a concurrent
 * request without ever getting an expiry set (a real leak: two Java-level calls racing against one
 * Redis key created for the first time). A single round trip is also strictly faster under load
 * than two.
 *
 * <p>TD-SEC-022: fails OPEN, not closed, when Redis itself is unreachable — see {@link #tryConsume}
 * for the full rationale. This is a deliberate availability-over-abuse-protection call for exactly
 * this layer, not a general policy; nothing else in this codebase makes that trade automatically.
 */
@Component
class RedisFixedWindowRateLimiter implements RateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(RedisFixedWindowRateLimiter.class);

  // KEYS[1]: the counter key. ARGV[1]: window length in seconds.
  // Returns {currentCount, secondsRemainingInWindow} — TTL is read back in the same script so the
  // Retry-After value reflects the real remaining window, not a second, separately-timed call.
  @SuppressWarnings("PMD.LongVariable")
  private static final RedisScript<List> INCREMENT_AND_GET_TTL_SCRIPT =
      new DefaultRedisScript<>(
          """
          local current = redis.call('INCR', KEYS[1])
          if tonumber(current) == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
          end
          local ttl = redis.call('TTL', KEYS[1])
          return {current, ttl}
          """,
          List.class);

  private final StringRedisTemplate redisTemplate;

  /* package */ RedisFixedWindowRateLimiter(final StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  // PMD.GuardLogStatement false positive: every logged argument below is a direct accessor/cheap
  // call (an exception's own getClass().getSimpleName(), a private helper on an already-built
  // String), never a constructed/formatted value expensive enough to guard — same rationale as
  // AuthenticateWithPasswordService's own identical suppression. PMD.OnlyOneReturn: the fail-open
  // catch block's own early return and the normal-path return are two independent, equally valid
  // exits — same rationale as RateLimitIdentifiers' own class-wide suppression.
  @SuppressWarnings({"unchecked", "PMD.GuardLogStatement", "PMD.OnlyOneReturn"})
  @Override
  public RateLimitDecision tryConsume(final String key, final int limit, final Duration window) {
    final List<Long> result;
    try {
      result =
          redisTemplate.execute(
              INCREMENT_AND_GET_TTL_SCRIPT, List.of(key), String.valueOf(window.toSeconds()));
    } catch (final DataAccessException e) {
      // TD-SEC-022: fail OPEN, not closed. This class sits directly in front of every login,
      // token, and /platform/** request across all three security chains — propagating this
      // exception (the original behavior) turns a Redis outage into a total authentication
      // outage, directly trading away nfr-quality-attributes.md §2's own ≥99.5% availability
      // target for the sake of the anti-abuse layer's own uptime. That trade is backwards: the
      // rate limiter exists to protect the login/token surface, not to become a single point of
      // failure for it. A brief, real window with no anti-abuse protection during a genuine Redis
      // outage is the accepted cost — logged loudly (ERROR, every single occurrence, not
      // sampled/throttled) so the outage is visible and prompts a fix, not silent and permanent.
      // DataAccessException is Spring Data's own umbrella for every Redis-driver failure
      // (connection refused, timeout, pool exhaustion) — catching it here, not a narrower Lettuce-
      // specific type, means this stays correct if the underlying driver ever changes.
      // TD-FUT-011 (rate-limit observability) is the eventual home for a real alertable metric
      // here instead of a log line depending on someone watching it.
      LOG.error(
          "event=rate_limit_fail_open key_prefix={} reason={}",
          keyPrefixForLogging(key),
          e.getClass().getSimpleName());
      return new RateLimitDecision(true, 0, Duration.ZERO);
    }
    final long currentCount = result.get(0);
    // A -1 TTL (key exists with no expiry) should never happen — the script above always sets one
    // on first increment — but a -1 or -2 here would otherwise turn into a nonsensical negative
    // Retry-After; clamping to the configured window is the safe fallback, not a crash.
    final long ttlSeconds = result.get(1);
    final Duration retryAfter = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : window;
    return new RateLimitDecision(currentCount <= limit, currentCount, retryAfter);
  }

  // BR-DATA-01: the full key's own final segment is a SHA-256 hash of PII (an email/IP,
  // AntiAbuseRateLimitingFilter#hash) or, for the capacity layer, a raw organizationId — neither
  // belongs in a log line that outlives Redis itself and may have different retention/access
  // controls. Logging only the rule-name prefix (e.g. "ratelimit:login:account") still tells an
  // operator which specific rule degraded, which is all a fail-open incident investigation needs.
  private static String keyPrefixForLogging(final String key) {
    final int lastSeparator = key.lastIndexOf(':');
    return lastSeparator < 0 ? key : key.substring(0, lastSeparator);
  }
}
