package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.RefreshToken;
import java.util.Optional;

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
   * service's {@code presented.revoke()} + {@link #save} call (marking the OLD token consumed) is a
   * genuine update and must keep calling {@link #save}. Same rationale {@code
   * AccountRepository#insert}'s own identical addition documents.
   */
  void insert(RefreshToken refreshToken);

  /**
   * BR-ID-03: the reuse-detection cascade — every refresh token for the account, not just the one
   * that was presented for reuse.
   */
  void revokeAllActiveForAccount(AccountId accountId);
}
