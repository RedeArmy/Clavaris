package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-062: the check against a real Redis, whose answer to CONFIG GET and effect of CONFIG SET
 * are what the unit test can only assume. The container carries {@code Egx} (the test base starts
 * it that way); the test takes the flags away, sees the gauge say so, and puts them back.
 */
@SpringBootTest
@Testcontainers
class RedisKeyspaceEventsCheckIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private ApplicationContext context;
  @Autowired private StringRedisTemplate redis;
  @Autowired private MeterRegistry meters;

  @Test
  void seesTheFlagsThatAreThereAndTheOnesThatAreNot() throws Exception {
    final Object check = context.getBean("redisKeyspaceEventsCheck");
    final Method run = check.getClass().getDeclaredMethod("check");
    run.setAccessible(true);

    assertThat(run.invoke(check).toString())
        .as("the container carries Egx")
        .isEqualTo("CONFIGURED");
    assertThat(gauge()).isEqualTo(1.0);

    setFlags("");
    try {
      assertThat(run.invoke(check).toString()).isEqualTo("MISSING");
      assertThat(gauge()).isEqualTo(0.0);
    } finally {
      setFlags("Egx");
    }

    assertThat(run.invoke(check).toString()).isEqualTo("CONFIGURED");
    assertThat(gauge()).isEqualTo(1.0);
  }

  private void setFlags(final String flags) {
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Object>)
            connection -> {
              connection.serverCommands().setConfig("notify-keyspace-events", flags);
              return null;
            });
  }

  private double gauge() {
    return meters.get("clavaris.redis.keyspace_events.configured").gauge().value();
  }
}
