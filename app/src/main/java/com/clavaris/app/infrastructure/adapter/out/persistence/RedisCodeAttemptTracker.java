package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.app.infrastructure.adapter.out.security.RateLimitKeyHasher;
import com.clavaris.identity.application.usecase.codelockout.CodeAttemptTracker;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * ADR-0033: the Redis side of {@link CodeAttemptTracker}. Three keys per subject, all derived from
 * one keyed digest of the caller's key (an email address never reaches Redis in the clear, the same
 * reason {@link RateLimitKeyHasher} exists): a counter of consecutive failures, the lock itself
 * (whose TTL <em>is</em> the lock's length), and how many times the subject has been locked.
 *
 * <p>The attempt is taken before the code is judged, in one Lua script together with the lock
 * check, so any number of entries arriving together are judged only up to the limit (TD-SEC-092);
 * and locking is a second script that only fires when the count has reached the limit, so exactly
 * one failure sees it do so. The counter expires a day after the last failure, so it measures
 * consecutive failures and not a lifetime total; the lock history is kept a week, so a second lock
 * is longer than the first (doubling from the base up to the maximum), and a right code clears all
 * of it.
 *
 * <p>Fails open when Redis is unreachable, the same call the rate limiter makes and for the same
 * reason (TD-SEC-022): this sits in front of a sign-in, and Redis being down must not take it down
 * too. The cost is that, for that moment, wrong codes are not counted.
 */
@Component
class RedisCodeAttemptTracker implements CodeAttemptTracker {

  private static final Logger LOG = LoggerFactory.getLogger(RedisCodeAttemptTracker.class);

  // The three keys of a subject: consecutive failures, the lock, and how many locks it has had.
  private static final String FAIL_SUFFIX = ":fail";
  private static final String LOCK_SUFFIX = ":lock";
  private static final String LEVEL_SUFFIX = ":level";

  private static final long FAILURE_TTL_SECONDS = Duration.ofDays(1).toSeconds();
  private static final long HISTORY_TTL_SECONDS = Duration.ofDays(7).toSeconds();

  // KEYS: [1] consecutive failures, [2] the lock, [3] how many locks. ARGV: [1] limit, [2] base
  // lock
  // seconds, [3] maximum lock seconds, [4] failure TTL, [5] history TTL. Returns the lock length in
  // seconds when this failure locked the subject, 0 otherwise.
  private static final String LOCK_ONCE_THE_LIMIT_IS_REACHED =
      """
      if failures < tonumber(ARGV[1]) then
        return 0
      end
      local level = redis.call('INCR', KEYS[3])
      redis.call('EXPIRE', KEYS[3], tonumber(ARGV[5]))
      local seconds = tonumber(ARGV[3])
      if level <= 32 then
        seconds = math.min(tonumber(ARGV[2]) * (2 ^ (level - 1)), seconds)
      end
      seconds = math.floor(seconds)
      redis.call('SET', KEYS[2], '1', 'EX', seconds)
      redis.call('DEL', KEYS[1])
      return seconds
      """;

  // TD-SEC-092. Same KEYS and ARGV. Takes one attempt: 0 when the subject is locked or the limit's
  // worth of attempts is already in flight, 1 otherwise. The counter is bumped even for a refused
  // attempt, which is harmless: it only has to stay at or above the limit until the failure that
  // reached it locks (and deletes it) or a right code clears it.
  private static final RedisScript<Long> RESERVE_ATTEMPT_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('EXISTS', KEYS[2]) == 1 then
            return 0
          end
          local attempts = redis.call('INCR', KEYS[1])
          redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
          if attempts > tonumber(ARGV[1]) then
            return 0
          end
          return 1
          """,
          Long.class);

  // TD-SEC-092. A reserved attempt was wrong: nothing to count, lock only if the limit is reached.
  // Returns the lock length in seconds when this call locked the subject, 0 otherwise.
  private static final RedisScript<Long> SETTLE_FAILED_ATTEMPT_SCRIPT =
      new DefaultRedisScript<>(
          """
          local failures = tonumber(redis.call('GET', KEYS[1]) or '0')
          """
              + LOCK_ONCE_THE_LIMIT_IS_REACHED,
          Long.class);

  private final StringRedisTemplate redis;
  private final RateLimitKeyHasher keyHasher;
  private final int maxConsecutiveFailures;
  private final long baseLockSeconds;
  private final long maxLockSeconds;

  /* package */ RedisCodeAttemptTracker(
      final StringRedisTemplate redis,
      final RateLimitKeyHasher keyHasher,
      @Value("${clavaris.code-lockout.max-consecutive-failures:30}")
          final int maxConsecutiveFailures,
      @Value("${clavaris.code-lockout.base-lock-minutes:15}") final long baseLockMinutes,
      @Value("${clavaris.code-lockout.max-lock-hours:24}") final long maxLockHours) {
    this.redis = redis;
    this.keyHasher = keyHasher;
    this.maxConsecutiveFailures = maxConsecutiveFailures;
    this.baseLockSeconds = Duration.ofMinutes(baseLockMinutes).toSeconds();
    this.maxLockSeconds = Duration.ofHours(maxLockHours).toSeconds();
  }

  private String base(final String key) {
    return "codelock:" + keyHasher.hash(key);
  }

  @Override
  public boolean isLocked(final String key) {
    try {
      return Boolean.TRUE.equals(redis.hasKey(base(key) + LOCK_SUFFIX));
    } catch (final DataAccessException failure) {
      return failOpen("is_locked", failure, false);
    }
  }

  @Override
  public boolean tryReserveAttempt(final String key) {
    return reserve(key, maxConsecutiveFailures);
  }

  @Override
  public boolean tryReserveWithin(final String key, final int limit) {
    return reserve(key, limit);
  }

  private boolean reserve(final String key, final int limit) {
    final String base = base(key);
    try {
      final Long reserved =
          redis.execute(
              RESERVE_ATTEMPT_SCRIPT,
              List.of(base + FAIL_SUFFIX, base + LOCK_SUFFIX, base + LEVEL_SUFFIX),
              String.valueOf(limit),
              String.valueOf(baseLockSeconds),
              String.valueOf(maxLockSeconds),
              String.valueOf(FAILURE_TTL_SECONDS),
              String.valueOf(HISTORY_TTL_SECONDS));
      return reserved != null && reserved > 0;
    } catch (final DataAccessException failure) {
      return failOpen("reserve_attempt", failure, true);
    }
  }

  @Override
  public Failure settleFailedAttempt(final String key) {
    final String base = base(key);
    try {
      final Long lockSeconds =
          redis.execute(
              SETTLE_FAILED_ATTEMPT_SCRIPT,
              List.of(base + FAIL_SUFFIX, base + LOCK_SUFFIX, base + LEVEL_SUFFIX),
              String.valueOf(maxConsecutiveFailures),
              String.valueOf(baseLockSeconds),
              String.valueOf(maxLockSeconds),
              String.valueOf(FAILURE_TTL_SECONDS),
              String.valueOf(HISTORY_TTL_SECONDS));
      if (lockSeconds == null || lockSeconds <= 0) {
        return Failure.counted();
      }
      return new Failure(true, Duration.ofSeconds(lockSeconds));
    } catch (final DataAccessException failure) {
      return failOpen("settle_failed_attempt", failure, Failure.counted());
    }
  }

  @Override
  public void clear(final String key) {
    final String base = base(key);
    try {
      redis.delete(List.of(base + FAIL_SUFFIX, base + LOCK_SUFFIX, base + LEVEL_SUFFIX));
    } catch (final DataAccessException failure) {
      failOpen("clear", failure, null);
    }
  }

  private <T> T failOpen(final String operation, final DataAccessException failure, final T value) {
    LOG.error(
        "event=code_lockout_fail_open operation={} reason={}",
        operation,
        failure.getClass().getSimpleName());
    return value;
  }
}
