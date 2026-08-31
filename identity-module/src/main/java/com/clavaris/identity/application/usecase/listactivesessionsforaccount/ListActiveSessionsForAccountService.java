package com.clavaris.identity.application.usecase.listactivesessionsforaccount;

import java.util.Comparator;
import java.util.List;

/**
 * Orchestration for {@link ListActiveSessionsForAccountUseCase}. A thin, read-only pass-through —
 * everything this query needs already lives in {@link AccountActiveSessionsRepository}'s own source
 * of truth (the live {@code HttpSession} store), there is no domain invariant to apply on the way
 * out.
 */
public class ListActiveSessionsForAccountService implements ListActiveSessionsForAccountUseCase {

  private final AccountActiveSessionsRepository activeSessions;

  public ListActiveSessionsForAccountService(final AccountActiveSessionsRepository activeSessions) {
    this.activeSessions = activeSessions;
  }

  @Override
  public List<ActiveAccountSession> handle(final ListActiveSessionsForAccountQuery query) {
    // Most-recently-active first — the natural reading order for "which of my devices am I
    // actually using," and puts the row matching the caller's own current session near the top.
    return activeSessions.findAllByAccountId(query.accountId()).stream()
        .sorted(Comparator.comparing(ActiveAccountSession::lastAccessedAt).reversed())
        .toList();
  }
}
