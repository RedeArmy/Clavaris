package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.app.infrastructure.adapter.out.security.RateLimitKeyHasher;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * TD-SEC-095, against real Redis servers with a ceiling small enough to reach: why the abuse
 * counters have an instance of their own.
 *
 * <p>The counters' keys are built from what an anonymous caller types, so a stream of distinct
 * values fills the instance. With {@code noeviction} (what sessions need: dropping one signs a
 * person out) a full Redis refuses every write, which for the one that held both is a sign-in
 * outage and a switched-off rate limiter at the same moment. With {@code volatile-ttl} the counters
 * evict the keys nearest to expiry and keep working. That policy is only safe if every counter key
 * has a TTL, so the first test checks exactly that.
 */
@Testcontainers
class CountersRedisSeparationTest {

  // Small enough to fill in a moment, with room above an empty server's own footprint (about 1 MB).
  private static final String CEILING = "3mb";
  private static final int FLOOD_LIMIT = 400_000;

  private static final GenericContainer<?> SESSIONS =
      new GenericContainer<>(DockerImageName.parse("redis:7"))
          .withExposedPorts(6379)
          .withCommand("redis-server", "--maxmemory", CEILING, "--maxmemory-policy", "noeviction");

  private static final GenericContainer<?> COUNTERS_EVICTING =
      new GenericContainer<>(DockerImageName.parse("redis:7"))
          .withExposedPorts(6379)
          .withCommand(
              "redis-server", "--maxmemory", CEILING, "--maxmemory-policy", "volatile-ttl");

  private static final GenericContainer<?> COUNTERS_SHARED_WITH_SESSIONS =
      new GenericContainer<>(DockerImageName.parse("redis:7"))
          .withExposedPorts(6379)
          .withCommand("redis-server", "--maxmemory", CEILING, "--maxmemory-policy", "noeviction");

  // No ceiling: the TTL invariant is about the keys the code writes, not about memory.
  private static final GenericContainer<?> UNBOUNDED =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  private static CountersRedis sessionsRedis;
  private static CountersRedis unbounded;
  private static CountersRedis evicting;
  private static CountersRedis sharedWithSessions;

  @BeforeAll
  static void start() {
    SESSIONS.start();
    COUNTERS_EVICTING.start();
    COUNTERS_SHARED_WITH_SESSIONS.start();
    UNBOUNDED.start();
    unbounded = connect(UNBOUNDED);
    sessionsRedis = connect(SESSIONS);
    evicting = connect(COUNTERS_EVICTING);
    sharedWithSessions = connect(COUNTERS_SHARED_WITH_SESSIONS);
  }

  @AfterAll
  static void stop() {
    sessionsRedis.close();
    evicting.close();
    sharedWithSessions.close();
    unbounded.close();
  }

  private static CountersRedis connect(final GenericContainer<?> container) {
    return CountersRedis.dedicated(
        container.getHost(), container.getMappedPort(6379), "", Duration.ofSeconds(2));
  }

  private static RedisCodeAttemptTracker tracker(final CountersRedis redis) {
    return new RedisCodeAttemptTracker(
        redis, new RateLimitKeyHasher("a-test-key-hash-secret"), 30, 15, 24);
  }

  /** Adds distinct counters the way an anonymous caller does, until the server has had to act. */
  private static int flood(final CountersRedis redis, final Runnable untilDone) {
    final RedisCodeAttemptTracker tracker = tracker(redis);
    int sent = 0;
    try {
      while (sent < FLOOD_LIMIT) {
        tracker.tryReserveAttempt("sign-in-code:" + "org:attacker-typed-" + sent);
        sent++;
        if (sent % 500 == 0) {
          untilDone.run();
        }
      }
    } catch (ConditionReached reached) {
      // the condition the caller was waiting for
    }
    return sent;
  }

  private static final class ConditionReached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ConditionReached() {
      super(null, null, false, false);
    }
  }

  private static long evictedKeys(final CountersRedis redis) {
    final Properties stats =
        redis.template().execute((RedisCallback<Properties>) c -> c.serverCommands().info("stats"));
    return Long.parseLong(stats.getProperty("evicted_keys", "0"));
  }

  @Test
  void everyCounterKeyCarriesATtlWhichIsWhatMakesVolatileTtlSafe() {
    final CountersRedis redis = unbounded;
    final RedisCodeAttemptTracker tracker = tracker(redis);
    final RedisFixedWindowRateLimiter limiter =
        new RedisFixedWindowRateLimiter(redis, (n, t) -> {});

    // The attempt path, through to a lock (30 failures) and its history, then a clear.
    for (int i = 0; i < 31; i++) {
      tracker.tryReserveAttempt("sign-in-code:org:someone");
      tracker.settleFailedAttempt("sign-in-code:org:someone");
    }
    tracker.tryReserveAttempt("totp-code:account-1");
    tracker.tryReserveWithin("totp-challenge:challenge-1", 5);
    // The rate limiter's buckets, current and previous window.
    limiter.tryConsume("ratelimit:login:account:org:one", 5, Duration.ofMinutes(1));
    limiter.tryConsume("ratelimit:token:ip:203.0.113.9", 5, Duration.ofMinutes(1));

    final Set<String> keys = redis.template().keys("*");
    assertThat(keys).as("the paths above wrote counters").isNotEmpty();
    for (final String key : keys) {
      assertThat(redis.template().getExpire(key))
          .as("a counter key without a TTL could never be evicted by volatile-ttl: " + key)
          .isPositive();
    }
  }

  @Test
  void aFloodOfUniqueCountersOnTheirOwnEvictingInstanceNeitherStopsWritesNorTouchesSessions() {
    // Sessions: no TTL, as a session store holds them.
    for (int i = 0; i < 100; i++) {
      sessionsRedis.template().opsForValue().set("session:" + i, "x".repeat(64));
    }

    final int sent =
        flood(
            evicting,
            () -> {
              if (evictedKeys(evicting) > 0) {
                throw new ConditionReached();
              }
            });

    assertThat(evictedKeys(evicting))
        .as("the flood had to make the counters' instance evict (after %d attempts)", sent)
        .isPositive();
    assertThat(sent).as("and it did so before the safety limit").isLessThan(FLOOD_LIMIT);
    // Counters still work: a write succeeds, and the tracker answers normally.
    evicting.template().opsForValue().set("probe", "1", Duration.ofSeconds(30));
    assertThat(tracker(evicting).tryReserveAttempt("sign-in-code:org:a-real-person")).isTrue();
    // Sessions are exactly as they were, and the instance that holds them never evicted anything.
    assertThat(sessionsRedis.template().keys("session:*")).hasSize(100);
    assertThat(evictedKeys(sessionsRedis)).isZero();
    sessionsRedis.template().opsForValue().set("session:after-the-flood", "x");
  }

  @Test
  void theSameFloodOnTheInstanceThatAlsoHoldsSessionsMakesEveryWriteFail() {
    final String[] firstFailure = new String[1];
    final int sent =
        flood(
            sharedWithSessions,
            () -> {
              try {
                sharedWithSessions.template().opsForValue().set("session:probe", "x");
              } catch (DataAccessException refused) {
                firstFailure[0] = refused.getMostSpecificCause().getMessage();
                throw new ConditionReached();
              }
            });

    assertThat(firstFailure[0])
        .as("after %d unique counters Redis refuses writes: no session could be stored", sent)
        .contains("OOM");
    assertThatThrownBy(
            () -> sharedWithSessions.template().opsForValue().set("session:new-sign-in", "x"))
        .as("a sign-in that needs a session now fails, while the limiter fails open")
        .isInstanceOf(DataAccessException.class);
  }
}
