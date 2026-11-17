package com.clavaris.identity.application.usecase.revokesessionbysid;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Session;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RevokeSessionBySidUseCase}.
 *
 * <p>The {@code sid} of an ID token is the Session's recorded {@code oidc_sid}, or, for a Session
 * that predates recording one, the Session's own id (what a refreshed ID token reports in that
 * case), so both are tried. The Organization check is the same tenant boundary {@code
 * RevokeRefreshTokenService} keeps: a {@code sid} that names a Session of another Organization's
 * Account is not found.
 */
public class RevokeSessionBySidService implements RevokeSessionBySidUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RevokeSessionBySidService.class);

  private final RefreshTokenRepository refreshTokens;
  private final SessionRepository sessions;
  private final AccountRepository accounts;
  private final SecurityMetricsRecorder metrics;
  private final Clock clock;

  public RevokeSessionBySidService(
      final RefreshTokenRepository refreshTokens,
      final SessionRepository sessions,
      final AccountRepository accounts,
      final SecurityMetricsRecorder metrics,
      final Clock clock) {
    this.refreshTokens = refreshTokens;
    this.sessions = sessions;
    this.accounts = accounts;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  @Transactional
  public boolean handle(final RevokeSessionBySidCommand command) {
    final Optional<Session> found =
        sessions.findByOidcSid(command.sid()).or(() -> byOwnId(command));
    if (found.isEmpty()) {
      return false;
    }
    final Session session = found.get();
    final boolean ofThisOrganization =
        accounts
            .findOrganizationIdById(session.accountId())
            .filter(command.organizationId()::equals)
            .isPresent();
    if (!ofThisOrganization) {
      return false;
    }

    final Instant now = clock.instant();
    refreshTokens.revokeAllActiveForSession(session.id(), now);
    session.revoke(now);
    sessions.save(session);
    LOG.info(
        "event=session_revoked_by_logout organizationId={} accountId={} sessionId={}",
        command.organizationId(),
        session.accountId(),
        session.id());
    metrics.increment("clavaris.auth.session.revoked_by_logout");
    return true;
  }

  private Optional<Session> byOwnId(final RevokeSessionBySidCommand command) {
    try {
      return sessions.findById(UUID.fromString(command.sid()));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
