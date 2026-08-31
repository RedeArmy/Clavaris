package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.domain.model.AccountId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Implements {@link AccountActiveSessionsRepository} against real Spring Session machinery — the
 * bridge lives in {@code app}, same "identity-module never depends on spring-security-config"
 * discipline as every other port in this package.
 *
 * <p>Reads go straight to {@link FindByIndexNameSessionRepository}, not {@link SessionRegistry}:
 * {@code SessionRegistry}'s own {@code SessionInformation} carries only principal/sessionId/
 * lastRequest — no attributes, no creation time — so it can't answer this port's own {@code
 * findAllByAccountId}. One call to {@code findByIndexNameAndIndexValue} is exactly what {@code
 * SpringSessionBackedSessionRegistry} (the bean {@code PlatformDashboardSecurityConfig} declares)
 * already resolves {@code getAllSessions(principalName, false)} to internally, so this bridge is
 * reading the same live Redis-backed truth, just with the attributes {@code SessionRegistry}'s own
 * abstraction doesn't expose.
 *
 * <p>Revoke goes through {@link SessionRegistry#getSessionInformation}/{@code expireNow()} instead
 * of a raw repository delete — the exact, already-proven TD-SEC-031 mechanism ({@code
 * TenantSessionConcurrencyFilter}/{@code InvalidateAndContinueSessionExpiredStrategy} already
 * enforce it on this chain), reused here rather than reinvented.
 */
@Component
class AccountActiveSessionsRepositoryBridge implements AccountActiveSessionsRepository {

  private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
  private final SessionRegistry sessionRegistry;

  /* package */ AccountActiveSessionsRepositoryBridge(
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository,
      final SessionRegistry sessionRegistry) {
    this.sessionRepository = sessionRepository;
    this.sessionRegistry = sessionRegistry;
  }

  @Override
  public List<ActiveAccountSession> findAllByAccountId(final AccountId accountId) {
    final Map<String, ? extends Session> sessions =
        sessionRepository.findByIndexNameAndIndexValue(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
            accountId.value().toString());
    return sessions.values().stream().map(this::toActiveAccountSession).toList();
  }

  @Override
  public Optional<ActiveAccountSession> findByAccountIdAndSessionId(
      final AccountId accountId, final String sessionId) {
    // Filtered from the same one-call read above, not a second repository call keyed by
    // sessionId alone — this is what actually enforces "must belong to this accountId," the
    // ownership check RevokeAccountSessionService's own caller relies on.
    return findAllByAccountId(accountId).stream()
        .filter(session -> session.sessionId().equals(sessionId))
        .findFirst();
  }

  @Override
  public void revoke(final String sessionId) {
    final SessionInformation info = sessionRegistry.getSessionInformation(sessionId);
    // Absent means already gone (expired/logged out between page render and this call) — a
    // benign race, not an error; AccountActiveSessionsRepository's own Javadoc documents this
    // method as unconditional/no ownership check, so "nothing to do" is a valid outcome here.
    if (info != null) {
      info.expireNow();
    }
  }

  private ActiveAccountSession toActiveAccountSession(final Session session) {
    return new ActiveAccountSession(
        session.getId(),
        session.getAttribute(SessionDeviceAttributes.USER_AGENT),
        session.getAttribute(SessionDeviceAttributes.SOURCE_IP),
        session.getCreationTime(),
        session.getLastAccessedTime());
  }
}
