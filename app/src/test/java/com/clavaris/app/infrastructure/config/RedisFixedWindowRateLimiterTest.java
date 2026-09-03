package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * TD-SEC-001/ADR-0010 §6: a real Redis (the same {@code redis:7} image {@code docker-compose.yml}
 * runs, via a plain {@link GenericContainer} — no Testcontainers Redis module dependency needed for
 * one image), not a mock — the entire point of this class is the atomicity of its Lua script under
 * real concurrent access, which a mocked {@code StringRedisTemplate} could never actually prove.
 */
@Testcontainers
class RedisFixedWindowRateLimiterTest {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static RedisFixedWindowRateLimiter rateLimiter;

  // TD-FUT-011: a no-op recorder for every test that isn't specifically about metric emission —
  // recordsRateLimitDecisionsAsRealMetrics below uses a Mockito mock instead, against this same
  // real Redis, to prove the actual name/tags a decision emits.
  private static final SecurityMetricsRecorder NO_OP_METRICS = (name, tags) -> {};

  @BeforeAll
  static void startRedisAndBuildLimiter() {
    REDIS.start();
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    rateLimiter = new RedisFixedWindowRateLimiter(redisTemplate, NO_OP_METRICS);
  }

  @AfterAll
  static void stopRedis() {
    connectionFactory.destroy();
    REDIS.stop();
  }

  @Test
  void allowsAttemptsUpToTheLimitAndRejectsTheNextOne() {
    String key = "test:" + UUID.randomUUID();

    for (int attempt = 1; attempt <= 3; attempt++) {
      RateLimitDecision decision = rateLimiter.tryConsume(key, 3, Duration.ofMinutes(1));
      assertThat(decision.allowed())
          .as("attempt %d of 3 (the configured limit) must be allowed", attempt)
          .isTrue();
      assertThat(decision.currentCount()).isEqualTo(attempt);
    }

    RateLimitDecision fourthAttempt = rateLimiter.tryConsume(key, 3, Duration.ofMinutes(1));

    assertThat(fourthAttempt.allowed()).as("the 4th attempt exceeds the limit of 3").isFalse();
    assertThat(fourthAttempt.currentCount()).isEqualTo(4);
    assertThat(fourthAttempt.retryAfter())
        .as("Retry-After must be a real, bounded remaining-window value, never negative or zero")
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void twoDifferentKeysHaveCompletelyIndependentCounters() {
    String accountKey = "test:account:" + UUID.randomUUID();
    String ipKey = "test:ip:" + UUID.randomUUID();

    rateLimiter.tryConsume(accountKey, 1, Duration.ofMinutes(1));
    RateLimitDecision ipDecision = rateLimiter.tryConsume(ipKey, 1, Duration.ofMinutes(1));

    assertThat(ipDecision.allowed())
        .as("exhausting one identifier's counter must never affect an unrelated identifier's own")
        .isTrue();
  }

  // java:S2925: this waits for real Redis-clock buckets to actually roll over, not for an async
  // operation to finish — there is no state to poll for in the interim, so Awaitility-style
  // polling buys nothing over a plain sleep here; the whole point is proving real elapsed time
  // against Redis's own TIME command, not a mocked clock.
  //
  // TD-FUT-010: sleeping just past one window's length (the old assertion here, 1.5×) is no
  // longer sufficient to prove a full reset — that is now the *expected*, no-longer-buggy
  // behavior: the whole fix is that the bucket immediately after a burst still weighs some of
  // that burst against it. A true, zero-carryover reset needs the *previous* bucket (current
  // bucket - 1) to be a bucket nothing ever touched, which needs at least two full window-widths
  // of real elapsed time, not one.
  @SuppressWarnings("java:S2925")
  @Test
  void aFreshWindowTwoBucketsLaterStartsAtOneWithNoCarryover() throws InterruptedException {
    String key = "test:" + UUID.randomUUID();
    Duration oneSecondWindow = Duration.ofSeconds(1);
    rateLimiter.tryConsume(key, 1, oneSecondWindow);

    Thread.sleep(2200);
    RateLimitDecision afterTwoWindows = rateLimiter.tryConsume(key, 1, oneSecondWindow);

    assertThat(afterTwoWindows.allowed()).isTrue();
    assertThat(afterTwoWindows.currentCount())
        .as(
            "a bucket two full window-widths later must start counting from 1 again, with no "
                + "carryover from a burst that old")
        .isEqualTo(1);
  }

  // TD-FUT-010: this is the actual bug this row named — a burst timed against a real Redis-clock
  // window boundary. Landing two calls deterministically on opposite sides of a boundary without
  // flaking on real Redis timing is impractical (see the class-under-test's own Javadoc on
  // #isAllowed), so this proves the fix at the level that actually matters: the pure weighting
  // function tryConsume delegates to, exercised directly against hand-picked boundary values.
  @Test
  void isAllowedWeighsAFullPreviousBurstAgainstAFreshBucketRightAtTheBoundary() {
    long windowMicros = 60_000_000L; // 60s window, in microseconds
    int limit = 20;
    // A prior window that used its full limit, and we're at the very first instant of the next
    // window (elapsed=0) — the previous burst is still maximally "recent" and must count in full.
    boolean allowedRightAtBoundary =
        RedisFixedWindowRateLimiter.isAllowed(1, limit, 0, windowMicros, limit);

    assertThat(allowedRightAtBoundary)
        .as(
            "TD-FUT-010: a client that already spent the full limit in the previous window must "
                + "not immediately get another full limit's worth right at the boundary — this is "
                + "exactly the ~2x-limit burst this row exists to close")
        .isFalse();
  }

  @Test
  void isAllowedIgnoresAPreviousBurstOnceItsWeightHasFullyDecayed() {
    long windowMicros = 60_000_000L; // 60s window, in microseconds
    int limit = 20;
    // Right at the end of the current bucket (elapsed == windowMicros, the instant before the
    // *next* bucket begins), the previous bucket's weight has decayed to zero — a fresh limit,
    // unrelated to that old burst, must be allowed again.
    boolean allowedAtBucketEnd =
        RedisFixedWindowRateLimiter.isAllowed(limit, limit, windowMicros, windowMicros, limit);

    assertThat(allowedAtBucketEnd)
        .as("once the previous bucket's weight has fully decayed, a fresh limit is allowed again")
        .isTrue();
  }

  @Test
  void isAllowedNeverAllowsMoreThanTheLimitWhenNothingPrecededTheCurrentBucket() {
    long windowMicros = 60_000_000L; // 60s window, in microseconds
    int limit = 20;

    // No previous-bucket activity at all (previousCount=0, the common case for most keys most of
    // the time) — the weighting must fall away entirely and this must behave exactly like the old
    // plain fixed window: allowed up to and including the limit, blocked the moment it's exceeded,
    // regardless of how far into the bucket the attempt lands.
    for (long elapsedMicros = 0; elapsedMicros <= windowMicros; elapsedMicros += windowMicros / 4) {
      assertThat(
              RedisFixedWindowRateLimiter.isAllowed(limit, 0, elapsedMicros, windowMicros, limit))
          .as(
              "the %dth attempt with no prior bucket must still be allowed (elapsed=%d)",
              limit, elapsedMicros)
          .isTrue();
      assertThat(
              RedisFixedWindowRateLimiter.isAllowed(
                  limit + 1, 0, elapsedMicros, windowMicros, limit))
          .as(
              "the (limit+1)th attempt with no prior bucket must still be blocked (elapsed=%d)",
              elapsedMicros)
          .isFalse();
    }
  }

  // TD-SEC-022: proves the fail-open contract against a real dead connection, not a mocked
  // exception — a fresh LettuceConnectionFactory pointed at a port nothing listens on, so the
  // very first command genuinely fails to connect (ECONNREFUSED on loopback is effectively
  // immediate; the short commandTimeout below is a safety net for environments where it isn't).
  @Test
  void failsOpenWhenRedisIsUnreachable() {
    LettuceClientConfiguration shortTimeout =
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build();
    LettuceConnectionFactory deadFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("127.0.0.1", 1), shortTimeout);
    deadFactory.afterPropertiesSet();
    StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
    deadTemplate.afterPropertiesSet();
    RedisFixedWindowRateLimiter limiterAgainstDeadRedis =
        new RedisFixedWindowRateLimiter(deadTemplate, NO_OP_METRICS);

    try {
      RateLimitDecision decision =
          limiterAgainstDeadRedis.tryConsume("test:unreachable", 1, Duration.ofMinutes(1));

      assertThat(decision.allowed())
          .as(
              "TD-SEC-022: a Redis outage must fail OPEN — the caller must never be taken down "
                  + "by the rate limiter's own dependency being unavailable")
          .isTrue();
      assertThat(decision.currentCount())
          .as("a fail-open decision has no real count to report")
          .isZero();
    } finally {
      deadFactory.destroy();
    }
  }

  // TD-FUT-011: real name/tags, against a real Redis — the exact "which rule/endpoint caused a
  // block" attribution this row's own widened description named as missing.
  @Test
  void recordsRateLimitDecisionsAsRealMetrics() {
    SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);
    StringRedisTemplate sharedTemplate = new StringRedisTemplate(connectionFactory);
    sharedTemplate.afterPropertiesSet();
    RedisFixedWindowRateLimiter instrumented =
        new RedisFixedWindowRateLimiter(sharedTemplate, metrics);
    String key = "ratelimit:login_account:" + UUID.randomUUID();

    instrumented.tryConsume(key, 1, Duration.ofMinutes(1));
    verify(metrics)
        .increment(
            "clavaris.rate_limit.decision",
            "rule",
            "ratelimit:login_account",
            "outcome",
            "allowed");

    instrumented.tryConsume(key, 1, Duration.ofMinutes(1));
    verify(metrics)
        .increment(
            "clavaris.rate_limit.decision",
            "rule",
            "ratelimit:login_account",
            "outcome",
            "blocked");
  }

  // TD-SEC-021's own live-verification standard applied here: don't just read the Lua script and
  // assert it looks atomic — actually fire concurrent requests at one key and prove the total
  // allowed count never exceeds the configured limit, which only holds if INCR+EXPIRE really is
  // one indivisible operation against Redis.
  @Test
  void concurrentAttemptsAgainstTheSameKeyNeverAllowMoreThanTheLimit() throws Exception {
    String key = "test:concurrent:" + UUID.randomUUID();
    int limit = 20;
    int concurrentAttempts = 100;
    ExecutorService executor = Executors.newFixedThreadPool(20);
    AtomicInteger allowedCount = new AtomicInteger();

    try {
      List<Callable<Void>> tasks =
          java.util.stream.IntStream.range(0, concurrentAttempts)
              .<Callable<Void>>mapToObj(
                  _ ->
                      () -> {
                        RateLimitDecision decision =
                            rateLimiter.tryConsume(key, limit, Duration.ofMinutes(1));
                        if (decision.allowed()) {
                          allowedCount.incrementAndGet();
                        }
                        return null;
                      })
              .toList();
      List<Future<Void>> futures = executor.invokeAll(tasks);
      for (Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
    }

    assertThat(allowedCount.get())
        .as(
            "100 concurrent attempts against a limit of 20 must allow exactly 20, proving the "
                + "Lua script's atomicity under real concurrent load, not just single-threaded correctness")
        .isEqualTo(limit);
  }
}
