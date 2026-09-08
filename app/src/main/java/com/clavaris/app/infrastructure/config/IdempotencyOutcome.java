package com.clavaris.app.infrastructure.config;

/** The four possible outcomes of {@link IdempotencyKeyStore#claim} — see its own Javadoc. */
enum IdempotencyOutcome {
  /** No prior attempt under this key exists — the caller may proceed with the real mutation. */
  CLAIMED,
  /** A prior attempt under this key is still in flight — same request, not yet finished. */
  IN_PROGRESS,
  /** A prior attempt under this key already completed with the same request body — replay it. */
  REPLAY,
  /** This key was already used once, with a genuinely different request body — caller misuse. */
  CONFLICT
}
