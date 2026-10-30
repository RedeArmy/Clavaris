package com.clavaris.identity.application.usecase.codelockout;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A stand-in for the Redis tracker with the same rules (consecutive failures lock at the limit, the
 * lock doubles each time, a right code clears everything), so the use case tests exercise real
 * lockout behaviour. Time does not pass here: a lock lasts until {@link #endLock}.
 */
public final class InMemoryCodeAttemptTracker implements CodeAttemptTracker {

  private final int limit;
  private final Map<String, Integer> failures = new HashMap<>();
  private final Map<String, Integer> locks = new HashMap<>();
  private final Set<String> locked = new HashSet<>();

  public InMemoryCodeAttemptTracker(final int limit) {
    this.limit = limit;
  }

  @Override
  public boolean isLocked(final String key) {
    return locked.contains(key);
  }

  @Override
  public Failure recordFailure(final String key) {
    final int count = failures.merge(key, 1, Integer::sum);
    if (count < limit) {
      return Failure.counted();
    }
    failures.remove(key);
    final int level = locks.merge(key, 1, Integer::sum);
    locked.add(key);
    return new Failure(true, Duration.ofMinutes(15L * (1L << (level - 1))));
  }

  @Override
  public void clear(final String key) {
    failures.remove(key);
    locks.remove(key);
    locked.remove(key);
  }

  /** The pause ends (time passes); the history of locks stays. */
  public void endLock(final String key) {
    locked.remove(key);
  }

  public int failuresOf(final String key) {
    return failures.getOrDefault(key, 0);
  }
}
