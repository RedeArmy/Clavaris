package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.infrastructure.adapter.out.security.RateLimitKeyHasher;
import com.clavaris.identity.application.usecase.codelockout.CodeAttemptTracker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * ADR-0033, against a real Redis (the Lua script is the logic): consecutive failures lock at the
 * limit, the lock doubles each time it is earned, a right code clears everything, and the key
 * handed in never reaches Redis in the clear.
 */
@Testcontainers
class RedisCodeAttemptTrackerTest {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  @BeforeAll
  static void startRedis() {
    REDIS.start();
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
  }

  @AfterAll
  static void stopRedis() {
    connectionFactory.destroy();
    REDIS.stop();
  }

  // Limit 3, first lock 15 minutes, never longer than 1 hour.
  private static RedisCodeAttemptTracker tracker() {
    return new RedisCodeAttemptTracker(
        redisTemplate, new RateLimitKeyHasher("a-test-key-hash-secret"), 3, 15, 1);
  }

  private static String freshKey() {
    return "sign-in-code:" + UUID.randomUUID() + ":person@example.com";
  }

  @Test
  void failuresBelowTheLimitCountButDoNotLock() {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();

    assertThat(tracker.recordFailure(key).lockedNow()).isFalse();
    assertThat(tracker.recordFailure(key).lockedNow()).isFalse();

    assertThat(tracker.isLocked(key)).isFalse();
  }

  @Test
  void theFailureThatReachesTheLimitLocksAndOnlyThatOne() {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();
    tracker.recordFailure(key);
    tracker.recordFailure(key);

    CodeAttemptTracker.Failure third = tracker.recordFailure(key);

    assertThat(third.lockedNow()).isTrue();
    assertThat(third.lockedFor()).isEqualTo(Duration.ofMinutes(15));
    assertThat(tracker.isLocked(key)).isTrue();
  }

  @Test
  void aSecondLockIsTwiceAsLongAndNeverBeyondTheMaximum() {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();
    Duration first = lockOnce(tracker, key);
    redisTemplate.delete(lockKeyOf(key)); // time passes: the pause ends, the history stays
    Duration second = lockOnce(tracker, key);
    redisTemplate.delete(lockKeyOf(key));
    Duration third = lockOnce(tracker, key);
    redisTemplate.delete(lockKeyOf(key));
    Duration fourth = lockOnce(tracker, key);

    assertThat(first).isEqualTo(Duration.ofMinutes(15));
    assertThat(second).isEqualTo(Duration.ofMinutes(30));
    assertThat(third).isEqualTo(Duration.ofMinutes(60));
    assertThat(fourth).as("capped at the one-hour maximum").isEqualTo(Duration.ofHours(1));
  }

  // TD-SEC-092: the attempt is taken before the code is judged, so a burst cannot be judged past
  // the limit. Limit 3: of 64 requests that arrive together, exactly 3 may be judged.
  @Test
  void parallelAttemptsAreAdmittedUpToTheLimitAndNoFurther() throws Exception {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();
    int requests = 64;
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < requests; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return tracker.tryReserveAttempt(key);
              }));
    }
    start.countDown();
    int admitted = 0;
    for (Future<Boolean> result : results) {
      if (result.get(30, TimeUnit.SECONDS)) {
        admitted++;
      }
    }
    pool.shutdown();

    assertThat(admitted).isEqualTo(3);
  }

  @Test
  void theThirdReservedAttemptThatFailsLocksAndOnlyThatOneDoes() {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();
    for (int i = 0; i < 3; i++) {
      assertThat(tracker.tryReserveAttempt(key)).isTrue();
    }
    assertThat(tracker.tryReserveAttempt(key)).as("the fourth is not judged").isFalse();

    CodeAttemptTracker.Failure first = tracker.settleFailedAttempt(key);
    CodeAttemptTracker.Failure second = tracker.settleFailedAttempt(key);

    assertThat(first.lockedNow()).isTrue();
    assertThat(first.lockedFor()).isEqualTo(Duration.ofMinutes(15));
    assertThat(second.lockedNow()).as("already locked: nobody is told twice").isFalse();
    assertThat(tracker.isLocked(key)).isTrue();
    assertThat(tracker.tryReserveAttempt(key)).isFalse();
  }

  @Test
  void aReservedAttemptThatFailsBelowTheLimitDoesNotLock() {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();
    tracker.tryReserveAttempt(key);

    assertThat(tracker.settleFailedAttempt(key).lockedNow()).isFalse();
    assertThat(tracker.isLocked(key)).isFalse();
  }

  @Test
  void aRightCodeAfterReservingStartsTheCountAgain() {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();
    tracker.tryReserveAttempt(key);
    tracker.tryReserveAttempt(key);
    tracker.clear(key);

    for (int i = 0; i < 3; i++) {
      assertThat(tracker.tryReserveAttempt(key)).isTrue();
    }
    assertThat(tracker.tryReserveAttempt(key)).isFalse();
  }

  @Test
  void aRightCodeClearsTheCountTheLockAndTheHistory() {
    CodeAttemptTracker tracker = tracker();
    String key = freshKey();
    lockOnce(tracker, key);
    assertThat(tracker.isLocked(key)).isTrue();

    tracker.clear(key);

    assertThat(tracker.isLocked(key)).isFalse();
    assertThat(lockOnce(tracker, key))
        .as("history cleared, so the next lock is the first length again")
        .isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void aSubjectIsCountedApartFromAnother() {
    CodeAttemptTracker tracker = tracker();
    String locked = freshKey();
    String other = freshKey();

    lockOnce(tracker, locked);

    assertThat(tracker.isLocked(locked)).isTrue();
    assertThat(tracker.isLocked(other)).isFalse();
  }

  @Test
  void theAddressItselfNeverReachesRedis() {
    CodeAttemptTracker tracker = tracker();
    String key = "sign-in-code:" + UUID.randomUUID() + ":secret.person@example.com";

    tracker.recordFailure(key);

    var keys = redisTemplate.keys("codelock:*");
    assertThat(keys).isNotEmpty().noneMatch(stored -> stored.contains("secret.person"));
  }

  private static Duration lockOnce(final CodeAttemptTracker tracker, final String key) {
    CodeAttemptTracker.Failure failure = CodeAttemptTracker.Failure.counted();
    for (int attempt = 0; attempt < 3 && !failure.lockedNow(); attempt++) {
      failure = tracker.recordFailure(key);
    }
    assertThat(failure.lockedNow()).isTrue();
    return failure.lockedFor();
  }

  private static String lockKeyOf(final String key) {
    return "codelock:" + new RateLimitKeyHasher("a-test-key-hash-secret").hash(key) + ":lock";
  }
}
