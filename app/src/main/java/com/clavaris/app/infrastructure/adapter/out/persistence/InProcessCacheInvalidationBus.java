package com.clavaris.app.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link CacheInvalidationBus} that reaches only this process: what a single instance needs, and
 * what the unit tests of the caches use. {@link #invalidateOthers} does nothing, because there are
 * no others.
 */
final class InProcessCacheInvalidationBus implements CacheInvalidationBus {

  private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

  @Override
  public void subscribe(final String cache, final Consumer<String> evict) {
    listeners.computeIfAbsent(cache, _ -> new CopyOnWriteArrayList<>()).add(evict);
  }

  @Override
  public void invalidate(final String cache, final String key) {
    for (final Consumer<String> evict : listeners.getOrDefault(cache, List.of())) {
      evict.accept(key);
    }
  }

  @Override
  public void invalidateOthers(final String cache, final String key) {
    // Nobody else to tell.
  }
}
