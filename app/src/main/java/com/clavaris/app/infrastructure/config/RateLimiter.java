package com.clavaris.app.infrastructure.config;

import java.time.Duration;

/**
 * ADR-0010 §6/BR-ID-06/BR-ORG-05: the shared mechanism both rate-limiting layers (6.1 anti-abuse,
 * per-identifier; 6.2 capacity, per-Organization aggregate) are built on — a fixed-window counter
 * keyed by an arbitrary caller-supplied string, so the two layers differ only in *what key and
 * limit* they call this with, not in the counting mechanism itself.
 */
@FunctionalInterface
interface RateLimiter {

  /**
   * Consumes one attempt against {@code key}'s current window, creating the window (with the given
   * {@code window} duration) if none is active. Never throws for "limit exceeded" — that is a
   * normal, expected outcome callers branch on via {@link RateLimitDecision#allowed()}, not an
   * exceptional one.
   */
  RateLimitDecision tryConsume(String key, int limit, Duration window);
}
