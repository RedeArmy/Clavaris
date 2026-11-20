package com.clavaris.app.infrastructure.adapter.out.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * {@link CacheInvalidationBus} over a Redis channel on the counters connection ({@link
 * CountersRedis}, the one the rate limiter already depends on, so no new infrastructure).
 *
 * <p>A message is {@code instanceId|cache|key}. The instance that sent it ignores its own, because
 * the sender has already dealt with itself (see {@link CacheInvalidationBus#invalidate}). Nothing
 * here may fail the write that caused it: a publish that cannot reach Redis is logged and counted
 * out of band, and the TTL of the caches is what is left to bound the staleness.
 */
@Component
class RedisCacheInvalidationBus implements CacheInvalidationBus, MessageListener, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(RedisCacheInvalidationBus.class);

  /* package */ static final String CHANNEL = "clavaris:cache-invalidation";
  private static final String SEPARATOR = "|";

  private final StringRedisTemplate redis;
  private final MeterRegistry meters;
  private final String instanceId = UUID.randomUUID().toString();
  private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
  private static final long RETRY_SECONDS = 10;

  private final RedisConnectionFactory connectionFactory;
  private final ScheduledExecutorService retries;
  private final AtomicReference<RedisMessageListenerContainer> container = new AtomicReference<>();
  private volatile boolean closed;

  /* package */ RedisCacheInvalidationBus(
      final CountersRedis countersRedis, final MeterRegistry meters) {
    this.meters = meters;
    this.redis = countersRedis.template();
    this.connectionFactory =
        Objects.requireNonNull(
            redis.getConnectionFactory(), "the counters Redis template has no connection factory");
    this.retries =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              final Thread thread = new Thread(runnable, "cache-invalidation-subscribe");
              thread.setDaemon(true);
              return thread;
            });
    // TD-ARCH-062: whether this instance is hearing the others, 1 or 0.
    meters.gauge(
        "clavaris.redis.listener.subscribed",
        List.of(Tag.of("listener", "cache_invalidation")),
        this,
        bus -> bus.isSubscribed() ? 1 : 0);
    startListening();
  }

  /* package */ boolean isSubscribed() {
    final RedisMessageListenerContainer current = container.get();
    return current != null && current.isRunning();
  }

  // The application must start, and keep serving, with Redis unreachable (the rate limiter and the
  // lockout fail open for the same reason), so a subscription that cannot be made now is retried in
  // the background instead of failing the context. Until it succeeds this instance is deaf to the
  // other instances' messages and the TTL of its caches is what bounds the staleness, as it always
  // was.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void startListening() {
    if (closed) {
      return;
    }
    final RedisMessageListenerContainer attempt = new RedisMessageListenerContainer();
    try {
      attempt.setConnectionFactory(connectionFactory);
      attempt.addMessageListener(this, new ChannelTopic(CHANNEL));
      attempt.afterPropertiesSet();
      attempt.start();
      container.set(attempt);
    } catch (final RuntimeException failure) {
      // TD-PROC-036: counted, so a subscription that has been failing for hours can alert.
      meters
          .counter("clavaris.redis.listener.start_failed", "listener", "cache_invalidation")
          .increment();
      LOG.warn(
          "event=cache_invalidation_subscribe_failed reason={} retryInSeconds={}",
          failure.getClass().getSimpleName(),
          RETRY_SECONDS);
      destroyQuietly(attempt);
      retries.schedule(this::startListening, RETRY_SECONDS, TimeUnit.SECONDS);
    }
  }

  @Override
  public void subscribe(final String cache, final Consumer<String> evict) {
    listeners.computeIfAbsent(cache, _ -> new CopyOnWriteArrayList<>()).add(evict);
  }

  @Override
  public void invalidate(final String cache, final String key) {
    evictHere(cache, key);
    invalidateOthers(cache, key);
  }

  @Override
  public void invalidateOthers(final String cache, final String key) {
    try {
      redis.convertAndSend(CHANNEL, instanceId + SEPARATOR + cache + SEPARATOR + key);
    } catch (final DataAccessException failure) {
      // The write that caused this has already happened and must not fail for it.
      LOG.warn(
          "event=cache_invalidation_publish_failed cache={} reason={}",
          cache,
          failure.getClass().getSimpleName());
    }
  }

  @Override
  public void onMessage(final Message message, final byte[] pattern) {
    final String[] parts =
        new String(message.getBody(), StandardCharsets.UTF_8).split("\\" + SEPARATOR, 3);
    if (parts.length != 3 || instanceId.equals(parts[0])) {
      return;
    }
    evictHere(parts[1], parts[2]);
  }

  private void evictHere(final String cache, final String key) {
    for (final Consumer<String> evict : listeners.getOrDefault(cache, List.of())) {
      evict.accept(key);
    }
  }

  @Override
  public void close() {
    closed = true;
    retries.shutdownNow();
    destroyQuietly(container.getAndSet(null));
  }

  // PMD.AvoidCatchingGenericException: RedisMessageListenerContainer#destroy declares `throws
  // Exception`, and a failure while closing must never stop the shutdown that is calling this.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static void destroyQuietly(final RedisMessageListenerContainer toDestroy) {
    if (toDestroy == null) {
      return;
    }
    try {
      toDestroy.destroy();
    } catch (final Exception failure) {
      LOG.warn(
          "event=cache_invalidation_close_failed reason={}", failure.getClass().getSimpleName());
    }
  }
}
