package com.clavaris.app.infrastructure.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
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
  private final String instanceId = UUID.randomUUID().toString();
  private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
  private final RedisMessageListenerContainer container;

  /* package */ RedisCacheInvalidationBus(final CountersRedis countersRedis) {
    this.redis = countersRedis.template();
    this.container = new RedisMessageListenerContainer();
    container.setConnectionFactory(
        Objects.requireNonNull(
            redis.getConnectionFactory(), "the counters Redis template has no connection factory"));
    container.addMessageListener(this, new ChannelTopic(CHANNEL));
    container.afterPropertiesSet();
    container.start();
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

  // PMD.AvoidCatchingGenericException: RedisMessageListenerContainer#destroy declares `throws
  // Exception`, and a failure while closing must never stop the shutdown that is calling this.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Override
  public void close() {
    try {
      container.destroy();
    } catch (final Exception failure) {
      LOG.warn(
          "event=cache_invalidation_close_failed reason={}", failure.getClass().getSimpleName());
    }
  }
}
