package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.identity.domain.model.RefreshToken;
import com.clavaris.identity.domain.model.Session;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link IssueRefreshTokenUseCase}.
 *
 * <p>Logs its own {@code event=token_issued tokenType=refresh_token} line rather than relying on
 * {@code app}'s {@code TokenIssuanceEventLogger} (TD-SEC-016) to cover it — that class is wired as
 * an {@code OAuth2TokenCustomizer}, which only ever fires for JWT-shaped tokens {@code
 * JwtGenerator} produces; a refresh token is an opaque random value, never a JWT, so it
 * structurally never reaches that hook. Without this line, refresh-token issuance would be
 * invisible in the security-event log stream every other token type already appears in.
 */
public class IssueRefreshTokenService implements IssueRefreshTokenUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(IssueRefreshTokenService.class);

  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;

  public IssueRefreshTokenService(
      final SessionRepository sessions, final RefreshTokenRepository refreshTokens) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
  }

  // PMD.GuardLogStatement false positive, same reasoning as AuthenticateWithPasswordService's own
  // suppression — every logged argument is a cheap in-memory accessor.
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public IssueRefreshTokenResult handle(final IssueRefreshTokenCommand command) {
    final Session session = Session.open(command.accountId(), command.authorizedScopes());
    sessions.save(session);

    final String rawValue = RefreshTokenSecret.generateRawValue();
    final String hash = RefreshTokenSecret.hash(rawValue);
    final RefreshToken token =
        RefreshToken.issue(session.id(), command.accountId(), hash, command.expiresAt());
    refreshTokens.save(token);

    LOG.info(
        "event=token_issued tokenType=refresh_token accountId={} sessionId={}",
        command.accountId(),
        session.id());

    return new IssueRefreshTokenResult(session.id(), rawValue, command.expiresAt());
  }
}
