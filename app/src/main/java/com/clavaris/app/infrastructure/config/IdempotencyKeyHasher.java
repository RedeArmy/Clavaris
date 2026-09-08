package com.clavaris.app.infrastructure.config;

/**
 * A deterministic, keyed digest for the client-supplied {@code Idempotency-Key} header value {@link
 * IdempotencyKeyFilter} folds into a Redis key — same "hash a caller-controlled value before it
 * becomes part of a Redis key" discipline {@link RateLimitKeyHasher} (TD-SEC-023) already
 * establishes, applied here for the same BR-DATA-01 reason: a careless caller could plausibly put
 * something structured (an order id, a customer email) into their own idempotency key, and this
 * value otherwise lands directly in the Redis keyspace.
 *
 * <p>A thin wrapper around {@link HmacSha256Hasher}, same shape as {@link
 * RateLimitKeyHasher}/{@link BearerTokenHasher} — see either's own Javadoc for why each purpose
 * gets its own dedicated secret rather than one shared key: this hashes a value whose compromise
 * only re-exposes a client's own idempotency bookkeeping (never a bearer credential or a rate-limit
 * identity), so it must never share a secret with either of those higher- or differently-stakes
 * purposes.
 */
final class IdempotencyKeyHasher {

  private final HmacSha256Hasher delegate;

  /* package */ IdempotencyKeyHasher(final String secret) {
    this.delegate = new HmacSha256Hasher(secret);
  }

  /* package */ String hash(final String value) {
    return delegate.hash(value);
  }
}
