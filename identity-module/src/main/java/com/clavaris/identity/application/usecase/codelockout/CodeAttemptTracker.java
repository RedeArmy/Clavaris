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
   * Takes one attempt before the code is judged, in one step with checking the lock: while the
   * limit's worth of attempts are already in flight, or the subject is locked, a further one is
   * refused without being judged. Counting after judging lets any number of parallel requests all
   * be judged before the first failure is recorded, so a burst makes as many guesses as it has
   * requests (TD-SEC-092). An attempt that is never settled (an error while judging) stays counted:
   * the safe side.
   *
   * @return whether this attempt may be judged
   */
  boolean tryReserveAttempt(String key);

  /**
   * Takes one attempt out of a budget of {@code limit}, and never locks anything: for a counter
   * whose running out ends something else (a pending challenge) instead of pausing a subject.
   *
   * @return whether the budget still had an attempt
   */
  boolean tryReserveWithin(String key, int limit);

  /**
   * A reserved attempt turned out wrong. It was already counted when reserved; this only locks when
   * the count has reached the limit, so that exactly one failure sees it do so.
   */
  Failure settleFailedAttempt(String key);

  /** A right code: the subject starts again from zero, lock history included. */
  void clear(String key);
}
