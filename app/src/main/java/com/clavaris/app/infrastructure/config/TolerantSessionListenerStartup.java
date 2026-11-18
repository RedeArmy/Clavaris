package com.clavaris.app.infrastructure.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * TD-ARCH-056: Spring Session's own listener container (the one that hears expired and deleted
 * sessions) subscribes while the context starts, and a Redis that cannot be reached at that moment
 * ended the whole process, exactly as {@code CONFIG SET} did before it. The application has to
 * start, and keep serving, with Redis unreachable, so this container is not started by the context:
 * this class starts it, and while it cannot, retries in the background. Until it succeeds, session
 * expiry events are not heard and the once-a-minute cleanup is what removes the leftovers, which is
 * how the session store behaved before events existed.
 */
final class TolerantSessionListenerStartup implements BeanPostProcessor, SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(TolerantSessionListenerStartup.class);

  /* package */ static final String SESSION_LISTENER_CONTAINER =
      "springSessionRedisMessageListenerContainer";

  private static final long DEFAULT_RETRY_MILLIS = 10_000;

  private final long retryMillis;

  private final ScheduledExecutorService retries =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            final Thread thread = new Thread(runnable, "session-listener-start");
            thread.setDaemon(true);
            return thread;
          });

  /* package */ TolerantSessionListenerStartup() {
    this(DEFAULT_RETRY_MILLIS);
  }

  /* package */ TolerantSessionListenerStartup(final long retryMillis) {
    this.retryMillis = retryMillis;
  }

  private volatile boolean running;
  private volatile RedisMessageListenerContainer container;

  @Override
  public Object postProcessBeforeInitialization(final Object bean, final String beanName) {
    if (SESSION_LISTENER_CONTAINER.equals(beanName)
        && bean instanceof RedisMessageListenerContainer c) {
      c.setAutoStartup(false);
      container = c;
    }
    return bean;
  }

  @Override
  public void start() {
    running = true;
    attempt();
  }

  // PMD.AvoidCatchingGenericException: the failure type depends on the driver and on where in the
  // subscription it happens; every one of them means "try again later", never "stop the process".
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void attempt() {
    final RedisMessageListenerContainer target = container;
    if (!running || target == null || target.isRunning()) {
      return;
    }
    try {
      target.start();
    } catch (final RuntimeException failure) {
      LOG.warn(
          "event=session_listener_start_failed reason={} retryInMillis={}",
          failure.getClass().getSimpleName(),
          retryMillis);
      retries.schedule(this::attempt, retryMillis, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void stop() {
    running = false;
    retries.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
