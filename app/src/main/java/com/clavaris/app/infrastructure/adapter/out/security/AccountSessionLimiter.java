package com.clavaris.app.infrastructure.adapter.out.security;

import java.util.UUID;

/**
 * Makes room for one more browser session of an Account (TD-PERF-051). Called by the establisher
 * while it builds a new session; an implementation may end older ones so that the Account never has
 * more live sessions than the cap allows.
 */
@FunctionalInterface
public interface AccountSessionLimiter {

  /**
   * @param replacedSessionId the id of the session this one replaces (a re-login or a restore in
   *     the same browser), or {@code null}; it is not counted as another device
   */
  void makeRoomFor(UUID accountId, String replacedSessionId);

  /** No cap: for the tests of the establisher, which are about what it grants, not how many. */
  static AccountSessionLimiter unlimited() {
    return (accountId, replacedSessionId) -> {
      // Intentionally nothing to do.
    };
  }
}
