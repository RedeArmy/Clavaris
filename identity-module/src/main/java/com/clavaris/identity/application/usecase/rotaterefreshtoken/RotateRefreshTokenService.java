package com.clavaris.identity.application.usecase.rotaterefreshtoken;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.RefreshTokenReuseDetectedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.RefreshToken;
import com.clavaris.identity.domain.model.Session;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RotateRefreshTokenUseCase} — BR-ID-03, `test-strategy.md` §3's "single
 * highest-value security invariant in the system." A presented token maps to exactly one of three
 * outcomes: unknown/expired (ordinary failure), already revoked (reuse — the account-wide
 * revocation cascade), or active (rotate). See {@link RefreshToken}'s own Javadoc for why "already
 * revoked" alone, without walking the {@code rotatedFromId} chain, is the correct and sufficient
 * reuse signal in this implementation.
 */
public class RotateRefreshTokenService implements RotateRefreshTokenUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RotateRefreshTokenService.class);

  private final RefreshTokenRepository refreshTokens;
  private final SessionRepository sessions;
  private final AccountRepository accounts;

  // Descriptive over PMD's default LongVariable threshold, kept in full rather than abbreviated —
  // same convention already used for e.g. passwordCredential elsewhere.
  @SuppressWarnings("PMD.LongVariable")
  private final AccountTokenRevoker accountTokenRevoker;

  private final EventOutboxWriter outbox;

  public RotateRefreshTokenService(
      final RefreshTokenRepository refreshTokens,
      final SessionRepository sessions,
      final AccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      final EventOutboxWriter outbox) {
    this.refreshTokens = refreshTokens;
    this.sessions = sessions;
    this.accounts = accounts;
    this.accountTokenRevoker = accountTokenRevoker;
    this.outbox = outbox;
  }

  // PMD.GuardLogStatement false positive, same reasoning as AuthenticateWithPasswordService's own
  // suppression — every logged argument is a cheap in-memory accessor, not an expensive
  // computation.
  // noRollbackFor is load-bearing, not decorative: confirmed live (a real integration test
  // initially failed on exactly this) that throwing RefreshTokenReuseDetectedException at the end
  // of this method — Spring's default behavior for any unchecked exception inside
  // @Transactional — rolled back the revocation cascade this same exception is meant to report
  // *after* it already succeeded. The whole point of BR-ID-03's reuse response is "everything is
  // already revoked, and here's an error telling you that" — an exception that undoes the
  // revocation it's reporting would make every subsequent presentation of a supposedly-revoked
  // token succeed again, silently defeating the entire invariant.
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional(noRollbackFor = RefreshTokenReuseDetectedException.class)
  public RotateRefreshTokenResult handle(final RotateRefreshTokenCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final RefreshToken presented =
        refreshTokens.findByTokenHash(presentedHash).orElseThrow(InvalidRefreshTokenException::new);

    if (presented.isRevoked()) {
      handleReuse(presented.accountId());
      throw new RefreshTokenReuseDetectedException();
    }

    if (!presented.isActive()) {
      // Naturally expired, never rotated/revoked — an ordinary failure, not a compromise signal.
      throw new InvalidRefreshTokenException();
    }

    final Session session =
        sessions
            .findById(presented.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "RefreshToken "
                            + presented.id()
                            + " references a Session that doesn't"
                            + " exist — data integrity violated before reaching this use case"));
    // RFC 6749 §6, before any mutation: an over-scoped request must leave the presented token
    // fully usable for a later, correctly-scoped retry — see
    // RequestedScopeExceedsAuthorizedScopeException's own Javadoc for the real bug this ordering
    // fixes.
    if (!command.requestedScopes().isEmpty()
        && !session.scopes().containsAll(command.requestedScopes())) {
      throw new RequestedScopeExceedsAuthorizedScopeException();
    }

    session.touch();
    sessions.save(session);

    presented.revoke();
    refreshTokens.save(presented);

    final String newRawValue = RefreshTokenSecret.generateRawValue();
    final RefreshToken rotated =
        RefreshToken.rotatedFrom(
            presented, RefreshTokenSecret.hash(newRawValue), command.newExpiresAt());
    refreshTokens.save(rotated);

    LOG.info(
        "event=refresh_token_rotated accountId={} sessionId={}",
        presented.accountId(),
        session.id());

    // RFC 6749 §6: the grantED scope for this specific access token is the requested subset when
    // present, never automatically re-widened back to the full session grant.
    final List<String> grantedScopes =
        command.requestedScopes().isEmpty() ? session.scopes() : command.requestedScopes();

    return new RotateRefreshTokenResult(
        presented.accountId(),
        session.id(),
        grantedScopes,
        session.createdAt(),
        newRawValue,
        command.newExpiresAt());
  }

  // BR-ID-03: "revokes every active token for that account, not just the reused one" — completed
  // synchronously, before this method returns, not scheduled as a later side effect. The event
  // written to the outbox at the end is a best-effort alert on top of an already-finished
  // revocation, never a gate on it (RefreshTokenReuseDetectedEvent's own Javadoc).
  private void handleReuse(final AccountId accountId) {
    refreshTokens.revokeAllActiveForAccount(accountId);
    sessions.revokeAllActiveForAccount(accountId);
    accountTokenRevoker.revokeAllTokensFor(accountId);

    final OrganizationId organizationId =
        accounts
            .findById(accountId)
            .map(Account::organizationId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "RefreshToken references AccountId "
                            + accountId
                            + " that doesn't exist — data integrity violated before reaching this"
                            + " use case"));

    LOG.warn(
        "event=refresh_token_reuse_detected organizationId={} accountId={}",
        organizationId,
        accountId);

    outbox.write(
        "refresh_token.reuse_detected",
        accountId,
        RefreshTokenReuseDetectedEvent.of(accountId, organizationId));
  }
}
