package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

// TD-ARCH-056: start-up never depends on Redis; the session listener is started when it can be.
class TolerantSessionListenerStartupTest {

  private final TolerantSessionListenerStartup startup = new TolerantSessionListenerStartup(20);
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
  void stopsRetryingOnceStopped() throws Exception {
    startup.postProcessBeforeInitialization(
        container, TolerantSessionListenerStartup.SESSION_LISTENER_CONTAINER);
    doThrow(new RedisConnectionFailureException("down")).when(container).start();

    startup.start();
    startup.stop();
    Thread.sleep(150);

    assertThat(startup.isRunning()).isFalse();
    verify(container, times(1)).start();
  }

  @Test
  void doesNothingWhenThereWasNoContainerToStart() {
    startup.start();

    assertThat(startup.isRunning()).isTrue();
    doNothing().when(container).start();
    verify(container, never()).start();
    startup.stop();
  }
}
