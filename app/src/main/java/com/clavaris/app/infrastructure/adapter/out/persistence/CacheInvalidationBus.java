package com.clavaris.app.infrastructure.adapter.out.persistence;

import java.util.function.Consumer;

/**
 * TD-ARCH-049: tells every instance that a cached policy changed, so that a change binds on all of
 * them when the message arrives and not only when each instance's own TTL runs out.
 *
 * <p>The four policy caches ({@code Caching*}) stay what they were, short-TTL read-through caches;
 * this adds a way to drop one entry on the other instances at once. It is a hint on top of the TTL,
 * never a replacement for it: a message can be lost (Redis down, a restart in between), and the TTL
 * then still bounds the staleness, exactly as before. Delivery is at most once and unordered, which
 * is all that dropping an entry needs.
 */
interface CacheInvalidationBus {

  String SESSION_POLICY = "session-policy";
  String ACCOUNT_AUTHENTICATION_POLICY = "account-authentication-policy";
  String RATE_LIMIT_POLICY = "rate-limit-policy";
  String CLIENT_DOMAIN_BY_CLIENT = "client-domain-by-client";
  String CLIENT_DOMAIN_BY_HOSTNAME = "client-domain-by-hostname";

  /**
   * Drops {@code key} of {@code cache} on this instance and tells the others to. For a writer that
   * did not put the new value into its own cache.
   */
  void invalidate(String cache, String key);

  /**
   * Tells the other instances to drop {@code key} of {@code cache}; this instance is left alone.
   * For a writer that has just put the new value into its own cache (write-through).
   */
  void invalidateOthers(String cache, String key);

  /** Registers what this instance does when {@code cache} is told to drop {@code key}. */
  void subscribe(String cache, Consumer<String> evict);
}
