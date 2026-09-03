package com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount;

import java.util.Comparator;
import java.util.List;

/**
 * Orchestration for {@link ListActiveSessionsForPlatformAccountUseCase} — TD-FUT-026, platform-tier
 * mirror of {@code listactivesessionsforaccount.ListActiveSessionsForAccountService}. Same thin,
 * read-only pass-through, same most-recently-active-first ordering.
 */
public class ListActiveSessionsForPlatformAccountService
    implements ListActiveSessionsForPlatformAccountUseCase {

  private final PlatformAccountActiveSessionsRepository activeSessions;

  public ListActiveSessionsForPlatformAccountService(
      final PlatformAccountActiveSessionsRepository activeSessions) {
    this.activeSessions = activeSessions;
  }

  @Override
  public List<ActivePlatformAccountSession> handle(
      final ListActiveSessionsForPlatformAccountQuery query) {
    return activeSessions.findAllByPlatformAccountId(query.platformAccountId()).stream()
        .sorted(Comparator.comparing(ActivePlatformAccountSession::lastAccessedAt).reversed())
        .toList();
  }
}
