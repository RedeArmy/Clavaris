package com.clavaris.identity.application.usecase.deferplatformsessionclosure;

import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.List;

/**
 * TD-ARCH-063: the operator accounts whose dashboard sessions still have to be closed because the
 * session store could not be reached when they were suspended or had their password reset. The
 * counterpart of {@code PendingSessionClosureRepository} for the tenant tier, written in the same
 * transaction as the state change.
 */
public interface PendingPlatformSessionClosureRepository {

  /** Records the account; a second call for the same account changes nothing. */
  void add(PlatformAccountId platformAccountId, Instant recordedAt);

  /** The oldest recorded accounts first, at most {@code limit}. */
  List<PlatformAccountId> oldest(int limit);

  void remove(PlatformAccountId platformAccountId);

  /** How many accounts are waiting, for the gauge an alert reads. */
  long count();
}
