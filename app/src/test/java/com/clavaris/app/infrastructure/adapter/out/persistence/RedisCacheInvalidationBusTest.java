package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicyProvider;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicySnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SessionPolicyRepository;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * TD-ARCH-049 against a real Redis: two buses stand for two instances of the application. A change
 * made on one drops the cached policy on the other without waiting for its TTL, an instance does
 * not evict itself twice, and a Redis that fails never fails the write.
 */
@Testcontainers
class RedisCacheInvalidationBusTest {

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

  private static RedisCacheInvalidationBus anInstance() {
    return new RedisCacheInvalidationBus(CountersRedis.sharing(redisTemplate));
  }

  @Test
  void aChangeOnOneInstanceDropsTheEntryOnTheOtherAndTheSenderEvictsItselfOnce() {
    try (RedisCacheInvalidationBus writer = anInstance();
        RedisCacheInvalidationBus reader = anInstance()) {
      List<String> onWriter = new CopyOnWriteArrayList<>();
      List<String> onReader = new CopyOnWriteArrayList<>();
      writer.subscribe(CacheInvalidationBus.SESSION_POLICY, onWriter::add);
      reader.subscribe(CacheInvalidationBus.SESSION_POLICY, onReader::add);

      writer.invalidate(CacheInvalidationBus.SESSION_POLICY, "org-1");

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(onReader).containsExactly("org-1"));
      // The writer evicted itself directly, and ignored the echo of its own message.
      assertThat(onWriter).containsExactly("org-1");
    }
  }

  @Test
  void invalidateOthersLeavesTheSenderAlone() {
    try (RedisCacheInvalidationBus writer = anInstance();
        RedisCacheInvalidationBus reader = anInstance()) {
      List<String> onWriter = new CopyOnWriteArrayList<>();
      List<String> onReader = new CopyOnWriteArrayList<>();
      writer.subscribe(CacheInvalidationBus.RATE_LIMIT_POLICY, onWriter::add);
      reader.subscribe(CacheInvalidationBus.RATE_LIMIT_POLICY, onReader::add);

      writer.invalidateOthers(CacheInvalidationBus.RATE_LIMIT_POLICY, "org-2");

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(onReader).containsExactly("org-2"));
      assertThat(onWriter).isEmpty();
    }
  }

  @Test
  void aMessageForAnotherCacheOrWithoutThreePartsIsIgnored() {
    try (RedisCacheInvalidationBus reader = anInstance()) {
      List<String> heard = new CopyOnWriteArrayList<>();
      reader.subscribe(CacheInvalidationBus.SESSION_POLICY, heard::add);

      redisTemplate.convertAndSend(RedisCacheInvalidationBus.CHANNEL, "garbage");
      redisTemplate.convertAndSend(
          RedisCacheInvalidationBus.CHANNEL,
          "someone|" + CacheInvalidationBus.RATE_LIMIT_POLICY + "|x");
      redisTemplate.convertAndSend(
          RedisCacheInvalidationBus.CHANNEL,
          "someone|" + CacheInvalidationBus.SESSION_POLICY + "|real");

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(heard).containsExactly("real"));
    }
  }

  @Test
  void aRedisThatFailsNeverFailsTheWrite() {
    StringRedisTemplate broken = mock(StringRedisTemplate.class);
    when(broken.getConnectionFactory()).thenReturn(connectionFactory);
    when(broken.convertAndSend(anyString(), anyString()))
        .thenThrow(new QueryTimeoutException("down"));
    try (RedisCacheInvalidationBus bus =
        new RedisCacheInvalidationBus(CountersRedis.sharing(broken))) {
      List<String> heard = new CopyOnWriteArrayList<>();
      bus.subscribe(CacheInvalidationBus.SESSION_POLICY, heard::add);

      assertThatCode(() -> bus.invalidate(CacheInvalidationBus.SESSION_POLICY, "org-3"))
          .doesNotThrowAnyException();

      // The sender still dropped its own entry; only the message to the others was lost.
      assertThat(heard).containsExactly("org-3");
    }
  }

  // The whole point, end to end with the real classes: instance B has the old policy cached; an
  // operator saves a new one through instance A; B serves the new one long before its TTL.
  @Test
  void aSavedSessionPolicyBindsOnAnotherInstanceBeforeItsTtlRunsOut() {
    UUID organization = UUID.randomUUID();
    AtomicReference<SessionPolicySnapshot> stored =
        new AtomicReference<>(new SessionPolicySnapshot(100, 10, 5, true));
    SessionPolicyProvider database = id -> stored.get();
    SessionPolicyRepository jpa = mock(SessionPolicyRepository.class);
    try (RedisCacheInvalidationBus busA = anInstance();
        RedisCacheInvalidationBus busB = anInstance()) {
      long oneHour = 3600;
      CachingSessionPolicyProvider instanceB =
          new CachingSessionPolicyProvider(database, oneHour, 100, busB);
      InvalidatingSessionPolicyRepository writerOnA =
          new InvalidatingSessionPolicyRepository(jpa, busA, Runnable::run);
      OrganizationId id = new OrganizationId(organization);
      assertThat(instanceB.policyFor(id).maximumLifetimeMinutes()).isEqualTo(100);

      stored.set(new SessionPolicySnapshot(999, 10, 5, true));
      writerOnA.save(SessionPolicy.define(organization, 999, 10, 5, true));

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(instanceB.policyFor(id).maximumLifetimeMinutes()).isEqualTo(999));
    }
  }
}
