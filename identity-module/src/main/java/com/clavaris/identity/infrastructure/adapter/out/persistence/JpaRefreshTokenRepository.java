package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.RefreshToken} (framework-free) and
 * {@link RefreshTokenEntity}.
 */
@Repository
class JpaRefreshTokenRepository implements RefreshTokenRepository {

  private final SpringDataRefreshTokenJpaRepository refreshTokens;

  /* package */ JpaRefreshTokenRepository(final SpringDataRefreshTokenJpaRepository refreshTokens) {
    this.refreshTokens = refreshTokens;
  }

  @Override
  public Optional<RefreshToken> findByTokenHash(final String tokenHash) {
    return refreshTokens.findByTokenHash(tokenHash).map(this::toDomain);
  }

  @Override
  public void save(final RefreshToken refreshToken) {
    refreshTokens.save(
        new RefreshTokenEntity(
            refreshToken.id(),
            refreshToken.sessionId(),
            refreshToken.accountId().value(),
            refreshToken.tokenHash(),
            refreshToken.rotatedFromId().orElse(null),
            refreshToken.issuedAt(),
            refreshToken.expiresAt(),
            refreshToken.revokedAt().orElse(null)));
  }

  @Override
  public void revokeAllActiveForAccount(final AccountId accountId) {
    refreshTokens.revokeAllActiveForAccount(accountId.value(), Instant.now());
  }

  private RefreshToken toDomain(final RefreshTokenEntity entity) {
    return RefreshToken.reconstitute(
        entity.getId(),
        entity.getSessionId(),
        new AccountId(entity.getAccountId()),
        entity.getTokenHash(),
        entity.getRotatedFromId(),
        entity.getIssuedAt(),
        entity.getExpiresAt(),
        entity.getRevokedAt());
  }
}
