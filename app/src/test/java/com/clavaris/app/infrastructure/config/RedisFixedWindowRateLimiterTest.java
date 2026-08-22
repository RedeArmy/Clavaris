package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

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

  @BeforeAll
  static void startRedisAndBuildLimiter() {
    REDIS.start();
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    rateLimiter = new RedisFixedWindowRateLimiter(redisTemplate);
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

  @Test
  void theWindowExpiresAndAFreshOneStartsAtOne() throws InterruptedException {
    String key = "test:" + UUID.randomUUID();
    Duration oneSecondWindow = Duration.ofSeconds(1);
    rateLimiter.tryConsume(key, 1, oneSecondWindow);

    // Real wall-clock wait, not a mocked clock — proving the actual TTL Redis applied, not an
    // assumption about what the EXPIRE call was supposed to do.
    Thread.sleep(1500);
    RateLimitDecision afterExpiry = rateLimiter.tryConsume(key, 1, oneSecondWindow);

    assertThat(afterExpiry.allowed()).isTrue();
    assertThat(afterExpiry.currentCount())
        .as(
            "a fresh window must start counting from 1 again, not carry over the expired one's count")
        .isEqualTo(1);
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
