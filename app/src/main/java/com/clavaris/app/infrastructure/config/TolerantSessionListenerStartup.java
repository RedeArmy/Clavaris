package com.clavaris.app.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
  private volatile boolean running;
  private final AtomicReference<RedisMessageListenerContainer> container = new AtomicReference<>();

  private final ScheduledExecutorService retries =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            final Thread thread = new Thread(runnable, "session-listener-start");
            thread.setDaemon(true);
            return thread;
          });

  // TD-PROC-036: a Supplier, not a registry: this post-processor is created before almost
  // everything else, when the registry may not exist yet, so it is looked up when a failure needs
  // counting and not before.
  private final Supplier<MeterRegistry> meters;

  /* package */ TolerantSessionListenerStartup(final Supplier<MeterRegistry> meters) {
    this(DEFAULT_RETRY_MILLIS, meters);
  }

  /* package */ TolerantSessionListenerStartup(
      final long retryMillis, final Supplier<MeterRegistry> meters) {
    this.retryMillis = retryMillis;
    this.meters = meters;
  }

  @Override
  public Object postProcessBeforeInitialization(final Object bean, final String beanName) {
    if (SESSION_LISTENER_CONTAINER.equals(beanName)
        && bean instanceof RedisMessageListenerContainer listener) {
      listener.setAutoStartup(false);
      container.set(listener);
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
    final RedisMessageListenerContainer target = container.get();
    if (!running || target == null || target.isRunning()) {
      return;
    }
    try {
      target.start();
    } catch (final RuntimeException failure) {
      countFailure();
      LOG.warn(
          "event=session_listener_start_failed reason={} retryInMillis={}",
          failure.getClass().getSimpleName(),
          retryMillis);
      retries.schedule(this::attempt, retryMillis, TimeUnit.MILLISECONDS);
    }
  }

  // TD-PROC-036: one increment per failed attempt, so while the listener stays deaf the counter
  // keeps rising and an alert can say so (SessionListenerNotSubscribed); until now it was only a
  // warn line.
  private void countFailure() {
    final MeterRegistry registry = meters.get();
    if (registry != null) {
      registry.counter("clavaris.redis.listener.start_failed", "listener", "session").increment();
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
