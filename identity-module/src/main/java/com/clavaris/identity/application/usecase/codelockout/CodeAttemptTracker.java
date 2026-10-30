package com.clavaris.identity.application.usecase.codelockout;

import java.time.Duration;

/**
 * Outbound port: counts the consecutive wrong one-time codes presented for one subject and locks
 * the method once there are too many (SP 800-63B-4 §3.1.3.2 and §3.2.2). Implemented in {@code app}
 * on Redis, next to the rate limiter it complements.
 *
 * <p>This is not a rate limit. A rate limit forgets after its window, so a patient guesser who
 * stays under it keeps guessing for ever; this counts <em>consecutive</em> failures, which only a
 * success (or a day without any failure) clears, and asking for a new code does not.
 *
 * <p>The key is opaque here: the caller builds it (see {@link CodeLockout}) so that an address with
 * no account is counted exactly like one with an account, and the adapter hashes it before storing
 * it, so no email address is kept in Redis.
 */
public interface CodeAttemptTracker {

  /** What recording a failure did. */
  record Failure(boolean lockedNow, Duration lockedFor) {

    /** A failure that did not lock anything. */
    public static Failure counted() {
      return new Failure(false, Duration.ZERO);
    }
  }

  /**
   * @return whether the subject is locked right now
   */
  boolean isLocked(String key);

  /**
   * Counts one wrong code. When it is the one that reaches the limit, the subject is locked for a
   * time that doubles with each lock until a day passes without failures.
   */
  Failure recordFailure(String key);

  /** A right code: the subject starts again from zero, lock history included. */
  void clear(String key);
}
