package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.infrastructure.adapter.out.security.RateLimitKeyHasher;
import com.clavaris.identity.application.usecase.codelockout.CodeAttemptTracker;
import java.time.Duration;
import java.util.UUID;
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
    assertThat(keys).isNotEmpty();
    assertThat(keys).noneMatch(stored -> stored.contains("secret.person"));
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
