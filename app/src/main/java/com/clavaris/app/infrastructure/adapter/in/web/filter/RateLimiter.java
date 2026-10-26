package com.clavaris.app.infrastructure.adapter.in.web.filter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ADR-0010 §6/BR-ID-06/BR-ORG-05: the shared mechanism both rate-limiting layers (6.1 anti-abuse,
 * per-identifier; 6.2 capacity, per-Organization aggregate) are built on — a fixed-window counter
 * keyed by an arbitrary caller-supplied string, so the two layers differ only in *what key and
 * limit* they call this with, not in the counting mechanism itself.
 */
@FunctionalInterface
public interface RateLimiter {

  /**
   * Consumes one attempt against {@code key}'s current window, creating the window (with the given
   * {@code window} duration) if none is active. Never throws for "limit exceeded" — that is a
   * normal, expected outcome callers branch on via {@link RateLimitDecision#allowed()}, not an
   * exceptional one.
   */
  RateLimitDecision tryConsume(String key, int limit, Duration window);

  /**
   * TD-PERF-046: consumes one attempt against every check and returns one decision per check, in
   * order. A request that matches several rules (login matches three) used to make one call each;
   * an implementation that talks to a remote store can answer all of them in one round trip, and
   * must still consume every check even when an earlier one is over its limit, exactly as calling
   * {@link #tryConsume} for each did. This default does just that, one call per check, for
   * implementations with nothing to gain from batching.
   */
  default List<RateLimitDecision> tryConsumeAll(final List<RateLimitCheck> checks) {
    final List<RateLimitDecision> decisions = new ArrayList<>(checks.size());
    for (final RateLimitCheck check : checks) {
      decisions.add(tryConsume(check.key(), check.limit(), check.window()));
    }
    return decisions;
  }
}
