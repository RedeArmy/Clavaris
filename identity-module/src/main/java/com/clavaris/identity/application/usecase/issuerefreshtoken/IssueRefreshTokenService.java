package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
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
 * for the Account first — the same 4-part cascade {@code RotateRefreshTokenService}'s own BR-ID-03
 * reuse response uses ({@link RefreshTokenRepository#revokeAllActiveForAccount}, {@link
 * SessionRepository#revokeAllActiveForAccount}, {@link AccountTokenRevoker}, {@link
 * AccountSessionRevoker}), triggered by policy instead of a compromise signal. Ordering matters:
 * nothing new has been opened yet at that point, so there is no risk of the cascade catching the
 * session this same call is about to create.
 */
public class IssueRefreshTokenService implements IssueRefreshTokenUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(IssueRefreshTokenService.class);

  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;
  private final SecurityMetricsRecorder metrics;
  private final AccountRepository accounts;
  private final SessionPolicyProvider sessionPolicyProvider;

  // Descriptive over PMD's default LongVariable threshold, same convention
  // RotateRefreshTokenService's own identical fields already establish.
  @SuppressWarnings("PMD.LongVariable")
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable")
  private final AccountSessionRevoker accountSessionRevoker;

  @SuppressWarnings("java:S107") // one parameter per collaborating port, same rationale as
  // RotateRefreshTokenService's own identical suppression.
  public IssueRefreshTokenService(
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final SecurityMetricsRecorder metrics,
      final AccountRepository accounts,
      final SessionPolicyProvider sessionPolicyProvider,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.metrics = metrics;
    this.accounts = accounts;
    this.sessionPolicyProvider = sessionPolicyProvider;
    this.accountTokenRevoker = accountTokenRevoker;
    this.accountSessionRevoker = accountSessionRevoker;
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
  // PMD.GuardLogStatement false positive, same reasoning as handle()'s own identical suppression.
  // PMD.OnlyOneReturn: "multi-session handling enabled, nothing to do" and "disabled, run the
  // cascade" are two genuinely distinct outcomes — same "one exit per distinct outcome" rationale
  // every other early-return guard in this codebase already applies.
  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.OnlyOneReturn"})
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
    accountSessionRevoker.revokeAllSessionsFor(accountId);
  }
}
