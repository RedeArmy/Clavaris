package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

// TD-ARCH-056: start-up never depends on Redis; the session listener is started when it can be.
class TolerantSessionListenerStartupTest {

  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  private final TolerantSessionListenerStartup startup =
      new TolerantSessionListenerStartup(20, () -> meters);
  private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);

  @Test
  void takesTheSessionListenerContainerOutOfTheContextsAutomaticStart() {
    startup.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);

    verify(container).setAutoStartup(false);
  }

  @Test
  void leavesEveryOtherBeanAlone() {
    final RedisMessageListenerContainer other = mock(RedisMessageListenerContainer.class);

    assertThat(startup.postProcessBeforeInitialization(other, "someOtherContainer"))
        .isSameAs(other);
    assertThat(
            startup.postProcessBeforeInitialization(
                "text", "springSessionRedisMessageListenerContainer"))
        .isEqualTo("text");

    verify(other, never()).setAutoStartup(false);
  }

  @Test
  void startsTheContainerWhenRedisIsThere() {
    startup.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);

    startup.start();

    verify(container).start();
    assertThat(startup.isRunning()).isTrue();
    startup.stop();
  }

  @Test
  void aFailedStartDoesNotPropagateAndIsRetriedUntilItWorks() {
    startup.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);
    doThrow(new RedisConnectionFailureException("down"))
        .doThrow(new RedisConnectionFailureException("still down"))
        .doNothing()
        .when(container)
        .start();

    startup.start();

    assertThat(startup.isRunning()).as("the context is not told it failed").isTrue();
    verify(container, timeout(2000).times(3)).start();
    startup.stop();
  }

  @Test
  void stopsRetryingOnceStopped() {
    startup.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);
    doThrow(new RedisConnectionFailureException("down")).when(container).start();

    startup.start();
    startup.stop();

    assertThat(startup.isRunning()).isFalse();
    // Mockito waits the 150 ms (several retry periods) and then checks nothing else was started.
    verify(container, after(150).times(1)).start();
  }

  @Test
  void doesNothingWhenThereWasNoContainerToStart() {
    startup.start();

    assertThat(startup.isRunning()).isTrue();
    doNothing().when(container).start();
    verify(container, never()).start();
    startup.stop();
  }

  // TD-PROC-036: every failed attempt is counted, so a listener that stays deaf keeps the counter
  // rising and an alert can say so.
  @Test
  void everyFailedStartIsCounted() {
    startup.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);
    doThrow(new RedisConnectionFailureException("down")).when(container).start();

    startup.start();

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(
                        meters
                            .get("clavaris.redis.listener.start_failed")
                            .tag("listener", "session")
                            .counter()
                            .count())
                    .isGreaterThanOrEqualTo(2.0));
    startup.stop();
  }

  @Test
  void aMissingRegistryDoesNotStopTheRetry() {
    TolerantSessionListenerStartup noRegistry = new TolerantSessionListenerStartup(20, () -> null);
    noRegistry.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);
    doThrow(new RedisConnectionFailureException("down")).doNothing().when(container).start();

    noRegistry.start();

    org.mockito.Mockito.verify(container, org.mockito.Mockito.timeout(2000).times(2)).start();
    noRegistry.stop();
  }
}
