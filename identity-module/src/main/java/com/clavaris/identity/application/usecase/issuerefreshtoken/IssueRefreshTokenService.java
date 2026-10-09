package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
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
 *
 * <p>Clerk "Sessions" settings parity: before opening the new Session, checks the Account's own
 * Organization's {@link SessionPolicyProvider#policyFor}; when {@code
 * multiSessionHandlingEnabled()} is {@code false}, revokes every pre-existing Session/RefreshToken
 * and SAS authorization for the Account first — {@link RefreshTokenRepository
 * #revokeAllActiveForAccount}, {@link SessionRepository#revokeAllActiveForAccount}, {@link
 * AccountTokenRevoker}, a 3-part subset of {@code RotateRefreshTokenService}'s own BR-ID-03
 * reuse-response cascade, triggered by policy instead of a compromise signal.
 *
 * <p>Deliberately <strong>excludes</strong> {@code RotateRefreshTokenService}'s fourth cascade
 * step, {@code AccountSessionRevoker} (the hosted-UI {@code HttpSession}/{@code SessionRegistry}
 * layer): by the time this use case runs — the backend-to-backend {@code authorization_code}→token
 * exchange (ADR-0013, confidential clients only) — the browser's own hosted-login {@code
 * HttpSession} from the interactive login step that just happened was already registered in the
 * same {@code SessionRegistry}, under the same principal, by {@code
 * SpringSecurityAuthenticatedSessionEstablisher#establish} moments earlier. Calling {@code
 * AccountSessionRevoker#revokeAllSessionsFor} here would expire that brand-new browser session too
 * — logging the Account straight back out of the hosted self-service pages it just signed into —
 * which is the opposite of "the new session survives, only the old ones are revoked." BR-ID-03's
 * own reuse response has no such concern: there, nuking the current session along with everything
 * else is the correct, intended full lockout.
 */
// PMD.LongVariable: sessionPolicyProvider/accountTokenRevoker name exactly what they hold, same
// convention RotateRefreshTokenService's own identical fields already establish — a class-level
// suppression here, not one per occurrence, since AvoidDuplicateLiterals otherwise flags the
// repeated annotation string.
@SuppressWarnings("PMD.LongVariable")
public class IssueRefreshTokenService implements IssueRefreshTokenUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(IssueRefreshTokenService.class);

  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;
  private final SecurityMetricsRecorder metrics;
  private final AccountRepository accounts;
  private final SessionPolicyProvider sessionPolicyProvider;
  private final AccountTokenRevoker accountTokenRevoker;

  public IssueRefreshTokenService(
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final SecurityMetricsRecorder metrics,
      final AccountRepository accounts,
      final SessionPolicyProvider sessionPolicyProvider,
      final AccountTokenRevoker accountTokenRevoker) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.metrics = metrics;
    this.accounts = accounts;
    this.sessionPolicyProvider = sessionPolicyProvider;
    this.accountTokenRevoker = accountTokenRevoker;
  }

  // PMD.GuardLogStatement false positive, same reasoning as AuthenticateWithPasswordService's own
  // suppression — every logged argument is a cheap in-memory accessor.
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public IssueRefreshTokenResult handle(final IssueRefreshTokenCommand command) {
    revokeOtherSessionsIfMultiSessionHandlingDisabled(command.accountId());

    // TD-PERF-019: insert, not save — Session.open/RefreshToken.issue guarantee both are
    // brand-new aggregates, one per login. See SessionRepository#insert/RefreshTokenRepository
    // #insert's own Javadoc.
    final Session session = Session.open(command.accountId(), command.authorizedScopes());
    sessions.insert(session);

    final String rawValue = RefreshTokenSecret.generateRawValue();
    final String hash = RefreshTokenSecret.hash(rawValue);
    final RefreshToken token =
        RefreshToken.issue(session.id(), command.accountId(), hash, command.expiresAt());
    refreshTokens.insert(token);

    LOG.info(
        "event=token_issued tokenType=refresh_token accountId={} sessionId={}",
        command.accountId(),
        session.id());
    metrics.increment("clavaris.auth.token.issued", "tokenType", "refresh_token");

    return new IssueRefreshTokenResult(session.id(), rawValue, command.expiresAt());
  }

  // See this class's own Javadoc for the full rationale and ordering guarantee.
  // PMD.OnlyOneReturn does not flag a void method's bare early-return guard clause, so no
  // suppression for it is needed here (unlike RequireRecentAuthentication's boolean-returning
  // isStale, which does trip it); PMD.GuardLogStatement likewise doesn't flag this method's own
  // log call, unlike handle()'s.
  private void revokeOtherSessionsIfMultiSessionHandlingDisabled(final AccountId accountId) {
    final OrganizationId organizationId =
        accounts
            .findOrganizationIdById(accountId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Issuing a refresh token for AccountId "
                            + accountId
                            + " that doesn't exist — data integrity violated before reaching this"
                            + " use case"));
    if (sessionPolicyProvider.policyFor(organizationId).multiSessionHandlingEnabled()) {
      return;
    }

    LOG.info(
        "event=prior_sessions_revoked_multi_session_disabled organizationId={} accountId={}",
        organizationId,
        accountId);
    refreshTokens.revokeAllActiveForAccount(accountId);
    sessions.revokeAllActiveForAccount(accountId);
    accountTokenRevoker.revokeAllTokensFor(accountId);
  }
}
