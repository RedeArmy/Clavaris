package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.app.infrastructure.adapter.out.persistence.RedisKeyspaceEventsCheck.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;

// TD-ARCH-062: what the check concludes from what Redis says, and that it never throws.
class RedisKeyspaceEventsCheckTest {

  private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
  private final RedisConnection connection = mock(RedisConnection.class);
  private final RedisServerCommands server = mock(RedisServerCommands.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final RedisKeyspaceEventsCheck check = new RedisKeyspaceEventsCheck(factory, meters);

  private void redisSays(final String flags) {
    final Properties config = new Properties();
    config.setProperty(RedisKeyspaceEventsCheck.SETTING, flags);
    when(factory.getConnection()).thenReturn(connection);
    when(connection.serverCommands()).thenReturn(server);
    when(server.getConfig(RedisKeyspaceEventsCheck.SETTING)).thenReturn(config);
  }

  private double gauge() {
    return meters.get("clavaris.redis.keyspace_events.configured").gauge().value();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Egx", "xEg", "gxE", "AE", "Egx$", "KEA", "Eglx"})
  void theRequiredFlagsAreConfigured(final String flags) {
    redisSays(flags);

    assertThat(check.check()).isEqualTo(State.CONFIGURED);
    assertThat(gauge()).isEqualTo(1.0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "E", "Eg", "Ex", "gx", "K", "KA", "g$"})
  void aMissingFlagIsMissing(final String flags) {
    redisSays(flags);

    assertThat(check.check()).isEqualTo(State.MISSING);
    assertThat(gauge()).isEqualTo(0.0);
  }

  // Not a failure and never an alert: a managed service that forbids CONFIG is the case ADR-0045
  // made possible to run, and a Redis that is down is told apart by its own alerts.
  @Test
  void aForbiddenConfigIsUnknownAndDoesNotThrow() {
    when(factory.getConnection()).thenReturn(connection);
    when(connection.serverCommands()).thenReturn(server);
    when(server.getConfig(RedisKeyspaceEventsCheck.SETTING))
        .thenThrow(
            new org.springframework.data.redis.RedisSystemException("ERR unknown command", null));

    assertThat(check.check()).isEqualTo(State.UNKNOWN);
    assertThat(gauge()).isEqualTo(-1.0);
  }

  @Test
  void aRedisThatIsDownIsUnknownAndDoesNotThrow() {
    when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("down"));

    assertThat(check.check()).isEqualTo(State.UNKNOWN);
    assertThat(gauge()).isEqualTo(-1.0);
  }

  @Test
  void anAnswerWithoutTheSettingIsUnknown() {
    when(factory.getConnection()).thenReturn(connection);
    when(connection.serverCommands()).thenReturn(server);
    when(server.getConfig(RedisKeyspaceEventsCheck.SETTING)).thenReturn(new Properties());

    assertThat(check.check()).isEqualTo(State.UNKNOWN);
  }

  @Test
  void theGaugeFollowsTheSettingBothWays() {
    redisSays("");
    check.check();
    assertThat(gauge()).isEqualTo(0.0);

    redisSays("Egx");
    check.check();
    assertThat(gauge()).isEqualTo(1.0);
  }

  @Test
  void theGaugeStartsUnknownUntilTheFirstCheck() {
    assertThat(gauge()).isEqualTo(-1.0);
  }
}
