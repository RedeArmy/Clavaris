package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaRefreshTokenRepository}. {@code
 * application.usecase.rotaterefreshtoken.RotateRefreshTokenService} is the second consumer, same
 * precedent as {@code registeraccount.AccountRepository}'s own cross-use-case reuse.
 */
public interface RefreshTokenRepository {

  /**
   * BR-ID-03: looked up by hash, never by the raw value — the raw value is never persisted anywhere
   * (see {@link RefreshToken}'s own Javadoc), so the caller must hash first.
   */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  void save(RefreshToken refreshToken);

  /**
   * TD-PERF-019: same write as {@link #save}, for the call sites that know for a fact this {@code
   * RefreshToken} has never been persisted before — {@code IssueRefreshTokenService} (a fresh
   * login) and {@code RotateRefreshTokenService}'s own newly-{@code rotatedFrom} token. That same
   * service consumes the OLD (presented) token via {@link #revokeIfActive}, not this method or
   * {@link #save} — see that method's own Javadoc. Same rationale {@code
   * AccountRepository#insert}'s own identical addition documents.
   */
  void insert(RefreshToken refreshToken);

  /**
   * BR-ID-03: the reuse-detection cascade — every refresh token for the account, not just the one
   * that was presented for reuse.
   */
  void revokeAllActiveForAccount(AccountId accountId);

  /**
   * BR-ID-03 / SDE-III review (2026-09-14): the guard against the exact TOCTOU race two concurrent
   * rotations of the same still-active token could otherwise exploit — see {@code
   * RotateRefreshTokenService}'s own Javadoc for the full failure mode this closes. A conditional
   * update ({@code WHERE id = ? AND revoked_at IS NULL}), not a plain {@link #save} after an
   * in-memory {@link RefreshToken#revoke()} — under PostgreSQL READ COMMITTED, the loser of two
   * concurrent calls against the same row is forced to re-check this WHERE clause against the
   * winner's already-committed revocation before it can report how many rows it touched, so it
   * reliably observes zero. Same race class {@code
   * activatesigningkeyfororganization.SigningKeyRepository#lockForRotation} closes with a Postgres
   * advisory lock — a conditional update is preferred here instead, since this is the module's
   * single highest-write-volume path and doesn't need to pay a lock's overhead on every call.
   *
   * @return {@code true} if this call actually revoked the token (this call won the race); {@code
   *     false} if it was already revoked by another call (a concurrent rotation, or genuine reuse)
   *     — the caller MUST treat {@code false} as reuse, never silently proceed as if it won.
   */
  boolean revokeIfActive(UUID refreshTokenId, Instant revokedAt);
}
