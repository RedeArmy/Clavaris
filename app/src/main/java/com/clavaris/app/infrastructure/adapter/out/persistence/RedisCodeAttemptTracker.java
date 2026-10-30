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
 * <p>Counting and locking happen in one Lua script, so two wrong codes arriving together cannot
 * both read the same count, and exactly one failure sees the counter reach the limit. The counter
 * expires a day after the last failure, so it measures consecutive failures and not a lifetime
 * total; the lock history is kept a week, so a second lock is longer than the first (doubling from
 * the base up to the maximum), and a right code clears all of it.
 *
 * <p>Fails open when Redis is unreachable, the same call the rate limiter makes and for the same
 * reason (TD-SEC-022): this sits in front of a sign-in, and Redis being down must not take it down
 * too. The cost is that, for that moment, wrong codes are not counted.
 */
@Component
class RedisCodeAttemptTracker implements CodeAttemptTracker {

  private static final Logger LOG = LoggerFactory.getLogger(RedisCodeAttemptTracker.class);

  private static final long FAILURE_TTL_SECONDS = Duration.ofDays(1).toSeconds();
  private static final long HISTORY_TTL_SECONDS = Duration.ofDays(7).toSeconds();

  // KEYS: [1] consecutive failures, [2] the lock, [3] how many locks. ARGV: [1] limit, [2] base
  // lock
  // seconds, [3] maximum lock seconds, [4] failure TTL, [5] history TTL. Returns the lock length in
  // seconds when this failure locked the subject, 0 otherwise.
  private static final RedisScript<Long> RECORD_FAILURE_SCRIPT =
      new DefaultRedisScript<>(
          """
          local failures = redis.call('INCR', KEYS[1])
          redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
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
          """,
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
      return Boolean.TRUE.equals(redis.hasKey(base(key) + ":lock"));
    } catch (final DataAccessException failure) {
      return failOpen("is_locked", failure, false);
    }
  }

  @Override
  public Failure recordFailure(final String key) {
    final String base = base(key);
    try {
      final Long lockSeconds =
          redis.execute(
              RECORD_FAILURE_SCRIPT,
              List.of(base + ":fail", base + ":lock", base + ":level"),
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
      return failOpen("record_failure", failure, Failure.counted());
    }
  }

  @Override
  public void clear(final String key) {
    final String base = base(key);
    try {
      redis.delete(List.of(base + ":fail", base + ":lock", base + ":level"));
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
