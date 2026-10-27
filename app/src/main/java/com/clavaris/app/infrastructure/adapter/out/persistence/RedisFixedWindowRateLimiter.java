package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimitCheck;
import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimitDecision;
import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimiter;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * ADR-0010 §6 (rate limiting is mandatory from day one, ADR-0004: Redis for exactly this).
 *
 * <p>TD-FUT-010 (closed 2026-09-02): a naive fixed window — one counter that resets the instant its
 * own TTL lapses — admits close to {@code 2×limit} to a client that times a burst against the
 * window's own boundary (a burst at t=59.9s and another at t=60.1s, e.g., are two entirely
 * unrelated counters as far as a plain fixed window is concerned). This class now implements the
 * standard <b>sliding window counter</b> approximation instead (the same technique Cloudflare's own
 * public rate-limiting writeup documents): clock-aligned {@code windowSeconds}-wide buckets, keyed
 * by {@code KEYS[1]:<bucket>}, {@code INCR}-ed as before; the decision weighs the *previous*
 * bucket's count by how much of the current instant's sliding window still overlaps it (linear
 * decay from full weight at the moment the current bucket starts, to zero weight at the moment it
 * ends). A steady stream at exactly the limit is never penalized (the weighted count never exceeds
 * a real limit-abiding client's own count); a genuine boundary-straddling burst is caught because
 * the previous bucket's count still counts, proportionally, against the new one. This is an
 * approximation, not a byte-exact sliding log — it assumes requests are evenly distributed within
 * each bucket — but it closes the ~2× gap down to a small, bounded overshoot with no added Redis
 * round trip and no new per-key data structure (still two plain counters, not a sorted set), which
 * is exactly the "bounded fix, not a rewrite" scope this row asked for.
 *
 * <p>Both the current bucket's {@code INCR} and its conditional {@code EXPIRE}, plus the previous
 * bucket's read, run as one Lua script — Redis executes a script atomically against every other
 * client, so there is no window where a key could be incremented by a concurrent request without
 * ever getting an expiry set (a real leak: two Java-level calls racing against one Redis key
 * created for the first time). A single round trip is also strictly faster under load than several.
 * The bucket boundary is computed from Redis's own {@code TIME} command, not the calling JVM's
 * clock — this runs behind every app instance in a horizontally-scaled deployment, and two
 * instances with even a small clock skew computing two different bucket numbers for the same real
 * instant would silently fragment one client's count across two unrelated keys.
 *
 * <p>TD-SEC-022: fails OPEN, not closed, when Redis itself is unreachable — see {@link #tryConsume}
 * for the full rationale. This is a deliberate availability-over-abuse-protection call for exactly
 * this layer, not a general policy; nothing else in this codebase makes that trade automatically.
 */
@Component
class RedisFixedWindowRateLimiter implements RateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(RedisFixedWindowRateLimiter.class);

  // KEYS[1]: the rule's own base key. ARGV[1]: window length in seconds.
  //
  // Buckets are aligned to clock-wide windowSeconds boundaries (bucket = floor(now / window)), not
  // "window seconds from this key's first use" — clock alignment is what makes "the previous
  // bucket" a meaningful, shared concept across concurrent callers, rather than each caller having
  // its own private window start time.
  //
  // Microsecond precision throughout (Redis's own TIME command already returns it), not whole
  // seconds: a whole-seconds elapsed-in-bucket value would be a constant 0 for any window ≤1s
  // (nowSeconds % windowSeconds is always 0 when windowSeconds divides nowSeconds's own second-
  // granularity clock, which is every value for a 1-second window), making the weight calculation
  // below meaningless right at the scale most useful for fast, deterministic tests.
  //
  // The current bucket's own key gets a TTL of 2×window, not window: it must still be readable as
  // *next* window's "previous bucket" after its own window ends, and only then may it expire.
  //
  // Returns {currentCount, previousCount, elapsedMicrosInCurrentBucket} — all plain integers,
  // deliberately. Lua-to-RESP numeric replies are truncated to integers (Redis's own long-standing
  // float-reply limitation), so the weighted-count arithmetic itself is done in Java (double
  // precision, see #isAllowed) from these raw integers, not inside the script. Retry-After is also
  // derived in Java from elapsedMicrosInCurrentBucket, not from the current key's own TTL — that
  // TTL is deliberately 2×window (below), longer than "time left in the caller-visible window",
  // since it also has to survive long enough to still be readable as *next* window's own "previous
  // bucket".
  //
  // TD-PERF-046: the script takes any number of counters, KEYS[i] with its window in ARGV[i], and
  // returns the three values for each in order, flattened ({current1, previous1, elapsed1,
  // current2, ...}). A request that matches several rules costs one round trip instead of one per
  // rule; a single counter is the same script with one key. Every key is consumed whatever an
  // earlier one returned. All keys must live on one Redis node (true of the standalone Redis this
  // runs against; a cluster would need a shared hash tag).
  @SuppressWarnings("PMD.LongVariable")
  private static final RedisScript<List> INCREMENT_AND_GET_WINDOW_STATE_SCRIPT =
      new DefaultRedisScript<>(
          """
          local now = redis.call('TIME')
          local nowMicros = (tonumber(now[1]) * 1000000) + tonumber(now[2])
          local out = {}
          for i = 1, #KEYS do
            local windowSeconds = tonumber(ARGV[i])
            local windowMicros = windowSeconds * 1000000
            local currentBucket = math.floor(nowMicros / windowMicros)
            local previousBucket = currentBucket - 1
            local currentKey = KEYS[i] .. ':' .. currentBucket
            local previousKey = KEYS[i] .. ':' .. previousBucket

            local currentCount = redis.call('INCR', currentKey)
            if tonumber(currentCount) == 1 then
              redis.call('EXPIRE', currentKey, windowSeconds * 2)
            end
            local previousCount = tonumber(redis.call('GET', previousKey)) or 0
            local elapsedMicrosInCurrent = nowMicros - (currentBucket * windowMicros)

            out[#out + 1] = currentCount
            out[#out + 1] = previousCount
            out[#out + 1] = elapsedMicrosInCurrent
          end
          return out
          """,
          List.class);

  private final StringRedisTemplate redisTemplate;
  private final SecurityMetricsRecorder metrics;

  /* package */ RedisFixedWindowRateLimiter(
      final StringRedisTemplate redisTemplate, final SecurityMetricsRecorder metrics) {
    this.redisTemplate = redisTemplate;
    this.metrics = metrics;
  }

  @Override
  public RateLimitDecision tryConsume(final String key, final int limit, final Duration window) {
    return tryConsumeAll(List.of(new RateLimitCheck(key, limit, window))).get(0);
  }

  // TD-PERF-046: one Lua call for every check of a request. @SuppressWarnings("unchecked"): the
  // script's reply is a raw List of Longs.
  @SuppressWarnings("unchecked")
  @Override
  public List<RateLimitDecision> tryConsumeAll(final List<RateLimitCheck> checks) {
    final List<Long> result;
    try {
      result =
          redisTemplate.execute(
              INCREMENT_AND_GET_WINDOW_STATE_SCRIPT,
              checks.stream().map(RateLimitCheck::key).toList(),
              checks.stream()
                  .map(check -> String.valueOf(check.window().toSeconds()))
                  .toArray(Object[]::new));
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
      // TD-FUT-011: real, alertable metric alongside the log line above — alert-rules.yml fires on
      // any occurrence at all, matching a Redis outage's own "this needs a human right now"
      // severity, the same way refresh-token reuse detection does below.
      return checks.stream().map(check -> failOpen(check.key(), e)).toList();
    }
    final List<RateLimitDecision> decisions = new ArrayList<>(checks.size());
    for (int i = 0; i < checks.size(); i++) {
      decisions.add(
          decide(checks.get(i), result.get(3 * i), result.get(3 * i + 1), result.get(3 * i + 2)));
    }
    return decisions;
  }

  private RateLimitDecision failOpen(final String key, final DataAccessException failure) {
    // Computed once, not inside the logging call: the arguments would otherwise be built even when
    // the level is off.
    final String rule = keyPrefixForLogging(key);
    final String reason = failure.getClass().getSimpleName();
    LOG.error("event=rate_limit_fail_open key_prefix={} reason={}", rule, reason);
    metrics.increment("clavaris.rate_limit.fail_open", "rule", rule, "reason", reason);
    return new RateLimitDecision(true, 0, Duration.ZERO);
  }

  private RateLimitDecision decide(
      final RateLimitCheck check,
      final long currentCount,
      final long previousCount,
      final long elapsedMicrosInCurrentBucket) {
    final long windowMicros = check.window().toSeconds() * 1_000_000L;
    // How long until the *current* bucket itself rolls over — a deliberately simple, bounded
    // approximation of "when might this be allowed again," not a precise unblock time (which,
    // under a sliding window, can depend on the next bucket's own count too). Always in
    // (0, window]: elapsedMicrosInCurrentBucket is always strictly less than windowMicros (it's a
    // remainder of that exact division in the script above).
    final Duration retryAfter =
        Duration.ofMillis((windowMicros - elapsedMicrosInCurrentBucket) / 1000);
    final boolean allowed =
        isAllowed(
            currentCount, previousCount, elapsedMicrosInCurrentBucket, windowMicros, check.limit());
    // TD-FUT-011: the exact "which rule/endpoint caused a block" attribution this row's own
    // widened description named as missing — every decision, not just fail-open ones, now carries
    // a real counter split by rule and outcome.
    metrics.increment(
        "clavaris.rate_limit.decision",
        "rule",
        keyPrefixForLogging(check.key()),
        "outcome",
        allowed ? "allowed" : "blocked");
    return new RateLimitDecision(allowed, currentCount, retryAfter);
  }

  /* package */ static boolean isAllowed(
      final long currentCount,
      final long previousCount,
      final long elapsedMicrosInCurrentBucket,
      final long windowMicros,
      final int limit) {
    final double weightOfPreviousBucket =
        (windowMicros - elapsedMicrosInCurrentBucket) / (double) windowMicros;
    final double weightedCount = (previousCount * weightOfPreviousBucket) + currentCount;
    return weightedCount <= limit;
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
