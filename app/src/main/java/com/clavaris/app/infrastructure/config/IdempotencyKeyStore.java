package com.clavaris.app.infrastructure.config;

/**
 * The shared mechanism {@link IdempotencyKeyFilter} builds on — mirrors {@link RateLimiter}'s own
 * "one small port, one Redis-backed implementation" shape (same module, same reasoning: the
 * atomicity this needs is only provable against a real Redis, never a mocked port, so {@link
 * RedisIdempotencyKeyStoreTest} covers that separately from this filter's own rule-evaluation
 * logic).
 */
interface IdempotencyKeyStore {

  /**
   * Attempts to claim {@code key} for a request whose body hashes to {@code requestBodyHash}.
   *
   * <p>Exactly one caller among any number of concurrent callers presenting the same {@code key}
   * ever receives {@link IdempotencyOutcome#CLAIMED} — every other concurrent caller sees {@link
   * IdempotencyOutcome#IN_PROGRESS} until the claiming caller calls {@link #complete}/{@link
   * #release}, after which a later caller sees {@link IdempotencyOutcome#REPLAY} (same body) or
   * {@link IdempotencyOutcome#CONFLICT} (a different body reusing the same key — caller misuse, not
   * a legitimate retry).
   */
  IdempotencyClaimResult claim(String key, String requestBodyHash);

  /**
   * Records the real outcome of a {@link IdempotencyOutcome#CLAIMED} attempt — every subsequent
   * {@link #claim} call under the same key and body hash now returns {@link
   * IdempotencyOutcome#REPLAY} with this exact response, until the stored entry's own TTL lapses.
   */
  void complete(String key, String requestBodyHash, IdempotentResponse response);

  /**
   * Releases a claim without caching anything — used when the real attempt failed with a
   * server-side error (see {@link IdempotencyKeyFilter}'s own Javadoc for why a 5xx is never
   * cached), so a legitimate retry under the same key is treated as a fresh attempt, not blocked
   * behind a response that was never actually usable.
   */
  void release(String key);
}
