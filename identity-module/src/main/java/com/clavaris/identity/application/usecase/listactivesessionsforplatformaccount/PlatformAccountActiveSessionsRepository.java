package com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount;

import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port — TD-FUT-026, platform-tier mirror of {@code listactivesessionsforaccount.
 * AccountActiveSessionsRepository}. One port, reused by both this package's own query and {@code
 * revokeplatformaccountsession}'s command — same "one port, several use cases" precedent.
 *
 * <p>{@link #revoke} is deliberately unconditional (no ownership check), same rationale as the
 * tenant-tier port this mirrors.
 */
public interface PlatformAccountActiveSessionsRepository {

  List<ActivePlatformAccountSession> findAllByPlatformAccountId(
      PlatformAccountId platformAccountId);

  Optional<ActivePlatformAccountSession> findByPlatformAccountIdAndSessionId(
      PlatformAccountId platformAccountId, String sessionId);

  void revoke(String sessionId);
}
