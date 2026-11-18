package com.clavaris.identity.application.usecase.deferaccountsessionclosure;

import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;

/**
 * TD-ARCH-057: the accounts whose browser sessions still have to be closed because the session
 * store could not be reached when their account was suspended, banned, deleted or had its password
 * reset. Written in the same transaction as that state change, so it exists exactly when the change
 * does.
 */
public interface PendingSessionClosureRepository {

  /** Records the account; a second call for the same account changes nothing. */
  void add(AccountId accountId, Instant recordedAt);

  /** The oldest recorded accounts first, at most {@code limit}. */
  List<AccountId> oldest(int limit);

  void remove(AccountId accountId);
}
