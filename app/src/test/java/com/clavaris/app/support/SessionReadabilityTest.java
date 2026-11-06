package com.clavaris.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.infrastructure.config.SessionJsonSerializerFactory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.testcontainers.containers.GenericContainer;

/**
 * TD-TEST-011: the helper has to see the sessions and has to name an attribute the serializer
 * refuses, or an {@code isEmpty()} on its result proves nothing. This puts a healthy session and
 * one holding the case that was seen in production-like runs (a {@code Long} inside a map) into a
 * real Redis and checks that only the second is reported, by name.
 */
class SessionReadabilityTest {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7").withExposedPorts(6379);
  private static LettuceConnectionFactory connectionFactory;
  private static final RedisSerializer<Object> SERIALIZER =
      SessionJsonSerializerFactory.create(SessionReadabilityTest.class.getClassLoader());

  @BeforeAll
  static void start() {
    REDIS.start();
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
  }

  @AfterAll
  static void stop() {
    connectionFactory.destroy();
    REDIS.stop();
  }

  private static void put(final String key, final String field, final Object value) {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      connection
          .hashCommands()
          .hSet(
              key.getBytes(StandardCharsets.UTF_8),
              field.getBytes(StandardCharsets.UTF_8),
              SERIALIZER.serialize(value));
    }
  }

  @Test
  void onlyTheAttributeTheSerializerRefusesIsReportedAndByName() {
    put("clavaris:sessions:sessions:good", "creationTime", 1_730_000_000_000L);
    put("clavaris:sessions:sessions:good", "sessionAttr:plain", "a string");
    final Map<String, Object> withAnUnlistedType = new LinkedHashMap<>();
    withAnUnlistedType.put("file", new java.io.File("not-allowed"));
    put("clavaris:sessions:sessions:bad", "sessionAttr:unlisted", withAnUnlistedType);

    final var unreadable = SessionReadability.unreadableAttributes(connectionFactory);

    assertThat(unreadable).hasSize(1);
    assertThat(unreadable.get(0))
        .contains("clavaris:sessions:sessions:bad")
        .contains("sessionAttr:unlisted")
        .contains("java.io.File");
  }

  @Test
  void theExpiryMarkersOfASessionAreNotMistakenForOne() {
    put("clavaris:sessions:sessions:expires:good", "anything", "not json at all");

    assertThat(SessionReadability.unreadableAttributes(connectionFactory))
        .noneMatch(line -> line.contains(":expires:"));
  }
}
