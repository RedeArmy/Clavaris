package com.clavaris.app.infrastructure.config;

import java.time.Duration;
import java.util.List;
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
 */
@Component
class RedisFixedWindowRateLimiter implements RateLimiter {

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

  @Override
  @SuppressWarnings("unchecked")
  public RateLimitDecision tryConsume(final String key, final int limit, final Duration window) {
    final List<Long> result =
        redisTemplate.execute(
            INCREMENT_AND_GET_TTL_SCRIPT, List.of(key), String.valueOf(window.toSeconds()));
    final long currentCount = result.get(0);
    // A -1 TTL (key exists with no expiry) should never happen — the script above always sets one
    // on first increment — but a -1 or -2 here would otherwise turn into a nonsensical negative
    // Retry-After; clamping to the configured window is the safe fallback, not a crash.
    final long ttlSeconds = result.get(1);
    final Duration retryAfter = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : window;
    return new RateLimitDecision(currentCount <= limit, currentCount, retryAfter);
  }
}
