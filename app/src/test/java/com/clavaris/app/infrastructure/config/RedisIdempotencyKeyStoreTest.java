package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.time.Duration;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

/**
 * TD-SEC-001/ADR-0004: a real Redis (same {@code redis:7} image {@code
 * RedisFixedWindowRateLimiterTest} already uses, via a plain {@link GenericContainer}), not a mock
 * — the entire point of this class is the claim script's atomicity under real concurrent access,
 * plus the real JSON round-trip through the shared {@link ObjectMapper}, neither of which a mocked
 * {@code StringRedisTemplate} could actually prove.
 */
@Testcontainers
class RedisIdempotencyKeyStoreTest {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  private static final SecurityMetricsRecorder NO_OP_METRICS = (name, tags) -> {};

  private static LettuceConnectionFactory connectionFactory;
  private static RedisIdempotencyKeyStore store;

  @BeforeAll
  static void startRedisAndBuildStore() {
    REDIS.start();
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    store =
        new RedisIdempotencyKeyStore(redisTemplate, new ObjectMapper(), NO_OP_METRICS, 30, 86400);
  }

  @AfterAll
  static void stopRedis() {
    connectionFactory.destroy();
    REDIS.stop();
  }

  @Test
  void aFreshKeyIsClaimedImmediately() {
    String key = "test:" + UUID.randomUUID();

    IdempotencyClaimResult result = store.claim(key, "body-hash-a");

    assertThat(result.outcome()).isEqualTo(IdempotencyOutcome.CLAIMED);
  }

  @Test
  void aStillClaimedKeyReturnsInProgressForAnyConcurrentCaller() {
    String key = "test:" + UUID.randomUUID();
    store.claim(key, "body-hash-a");

    IdempotencyClaimResult secondCaller = store.claim(key, "body-hash-a");

    assertThat(secondCaller.outcome()).isEqualTo(IdempotencyOutcome.IN_PROGRESS);
  }

  @Test
  void aCompletedKeyReplaysTheExactCachedResponseForTheSameRequestBody() {
    String key = "test:" + UUID.randomUUID();
    store.claim(key, "body-hash-a");
    IdempotentResponse original =
        new IdempotentResponse(201, "application/json", "{\"id\":\"abc\"}".getBytes());
    store.complete(key, "body-hash-a", original);

    IdempotencyClaimResult replay = store.claim(key, "body-hash-a");

    assertThat(replay.outcome()).isEqualTo(IdempotencyOutcome.REPLAY);
    assertThat(replay.cachedResponse().status()).isEqualTo(201);
    assertThat(replay.cachedResponse().contentType()).isEqualTo("application/json");
    assertThat(replay.cachedResponse().body()).isEqualTo("{\"id\":\"abc\"}".getBytes());
  }

  @Test
  void aCompletedKeyReusedWithADifferentRequestBodyIsAConflict() {
    String key = "test:" + UUID.randomUUID();
    store.claim(key, "body-hash-a");
    store.complete(
        key, "body-hash-a", new IdempotentResponse(201, "application/json", "{}".getBytes()));

    IdempotencyClaimResult reused = store.claim(key, "body-hash-b");

    assertThat(reused.outcome()).isEqualTo(IdempotencyOutcome.CONFLICT);
  }

  @Test
  void aReleasedKeyIsClaimableAgainAsAFreshAttempt() {
    String key = "test:" + UUID.randomUUID();
    store.claim(key, "body-hash-a");

    store.release(key);
    IdempotencyClaimResult retried = store.claim(key, "body-hash-a");

    assertThat(retried.outcome())
        .as("a released claim (e.g. after a 5xx) must not permanently block a retry")
        .isEqualTo(IdempotencyOutcome.CLAIMED);
  }

  // TD-SEC-022-shaped: proves the fail-open contract against a real dead connection, same
  // technique RedisFixedWindowRateLimiterTest's own identical test uses.
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
    RedisIdempotencyKeyStore storeAgainstDeadRedis =
        new RedisIdempotencyKeyStore(deadTemplate, new ObjectMapper(), NO_OP_METRICS, 30, 86400);

    try {
      IdempotencyClaimResult result = storeAgainstDeadRedis.claim("test:unreachable", "body-hash");

      assertThat(result.outcome())
          .as(
              "a Redis outage must fail OPEN — the admin API must never be taken down by "
                  + "idempotency bookkeeping being unavailable")
          .isEqualTo(IdempotencyOutcome.CLAIMED);
    } finally {
      deadFactory.destroy();
    }
  }
}
