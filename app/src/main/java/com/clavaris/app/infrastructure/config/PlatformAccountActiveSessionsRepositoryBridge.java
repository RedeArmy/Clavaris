package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ActivePlatformAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.PlatformAccountActiveSessionsRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Implements {@link PlatformAccountActiveSessionsRepository} — TD-FUT-026 (closed 2026-09-02),
 * platform-tier mirror of {@link AccountActiveSessionsRepositoryBridge}. Reuses the exact same two
 * shared, app-wide beans that bridge already depends on ({@link FindByIndexNameSessionRepository},
 * {@link SessionRegistry}) — Spring Session's own Redis-backed store and the {@code
 * SessionRegistry} wrapping it are both single, shared instances across every tier this deployment
 * serves, not tenant-only; see that bridge's own Javadoc for the full mechanism this one reuses
 * unchanged, beyond reading {@link SessionDeviceAttributes} for a {@code PlatformAccountId}'s own
 * sessions instead of an {@code AccountId}'s.
 */
@Component
class PlatformAccountActiveSessionsRepositoryBridge
    implements PlatformAccountActiveSessionsRepository {

  private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
  private final SessionRegistry sessionRegistry;

  /* package */ PlatformAccountActiveSessionsRepositoryBridge(
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository,
      final SessionRegistry sessionRegistry) {
    this.sessionRepository = sessionRepository;
    this.sessionRegistry = sessionRegistry;
  }

  @Override
  public List<ActivePlatformAccountSession> findAllByPlatformAccountId(
      final PlatformAccountId platformAccountId) {
    final Map<String, ? extends Session> sessions =
        sessionRepository.findByIndexNameAndIndexValue(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
            platformAccountId.value().toString());
    return sessions.values().stream().map(this::toActivePlatformAccountSession).toList();
  }

  @Override
  public Optional<ActivePlatformAccountSession> findByPlatformAccountIdAndSessionId(
      final PlatformAccountId platformAccountId, final String sessionId) {
    return findAllByPlatformAccountId(platformAccountId).stream()
        .filter(session -> session.sessionId().equals(sessionId))
        .findFirst();
  }

  @Override
  public void revoke(final String sessionId) {
    final SessionInformation info = sessionRegistry.getSessionInformation(sessionId);
    if (info != null) {
      info.expireNow();
    }
  }

  private ActivePlatformAccountSession toActivePlatformAccountSession(final Session session) {
    return new ActivePlatformAccountSession(
        session.getId(),
        session.getAttribute(SessionDeviceAttributes.USER_AGENT),
        session.getAttribute(SessionDeviceAttributes.SOURCE_IP),
        session.getCreationTime(),
        session.getLastAccessedTime());
  }
}
