package com.clavaris.identity.application.usecase.revokerefreshtoken;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.RefreshToken;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RevokeRefreshTokenUseCase}.
 *
 * <p>The whole Session ends, not only the presented token: a refresh token rotates on every use, so
 * the one a client holds may already be a rotated-away predecessor of the live one, and revoking
 * only the presented row would leave the live token minting. A presented token that was already
 * revoked (a rotated-away one, say) still ends the Session: presenting it is a request to stop the
 * chain, and ending it is the safe reading.
 *
 * <p>The Organization check keeps the tenant boundary (ADR-0010): the authenticated client belongs
 * to the Organization whose endpoint received the call, and a token of an Account of another
 * Organization is simply not found.
 */
public class RevokeRefreshTokenService implements RevokeRefreshTokenUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RevokeRefreshTokenService.class);

  private final RefreshTokenRepository refreshTokens;
  private final SessionRepository sessions;
  private final AccountRepository accounts;
  private final SecurityMetricsRecorder metrics;
  private final Clock clock;

  public RevokeRefreshTokenService(
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
  public boolean handle(final RevokeRefreshTokenCommand command) {
    final Optional<RefreshToken> found =
        refreshTokens.findByTokenHash(RefreshTokenSecret.hash(command.rawToken()));
    if (found.isEmpty()) {
      return false;
    }
    final RefreshToken token = found.get();
    final boolean ofThisOrganization =
        accounts
            .findOrganizationIdById(token.accountId())
            .filter(command.organizationId()::equals)
            .isPresent();
    if (!ofThisOrganization) {
      return false;
    }

    final Instant now = clock.instant();
    refreshTokens.revokeAllActiveForSession(token.sessionId(), now);
    sessions
        .findById(token.sessionId())
        .ifPresent(
            session -> {
              session.revoke(now);
              sessions.save(session);
            });
    // Never the token itself or its hash (BR-DATA-01).
    LOG.info(
        "event=refresh_token_revoked organizationId={} accountId={} sessionId={}",
        command.organizationId(),
        token.accountId(),
        token.sessionId());
    metrics.increment("clavaris.auth.refresh_token.revoked");
    return true;
  }
}
