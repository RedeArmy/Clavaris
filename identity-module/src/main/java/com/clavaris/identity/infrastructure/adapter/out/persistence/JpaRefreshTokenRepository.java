package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.RefreshToken;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; maps between {@code domain.model.RefreshToken} (framework-free) and
 * {@link RefreshTokenEntity}.
 *
 * <p>TD-PERF-019: {@code insert} calls {@link EntityManager#persist} directly, not {@code
 * SpringDataRefreshTokenJpaRepository#save} — this is the single highest-write-volume entity this
 * row named (a real new row on every login and every token refresh), so the merge()-vs-persist()
 * gotcha matters most here. See {@code RefreshTokenRepository#insert}'s own Javadoc for which call
 * sites that's safe for.
 */
@Repository
class JpaRefreshTokenRepository implements RefreshTokenRepository {

  private final SpringDataRefreshTokenJpaRepository refreshTokens;
  private final EntityManager entityManager;

  /* package */ JpaRefreshTokenRepository(
      final SpringDataRefreshTokenJpaRepository refreshTokens, final EntityManager entityManager) {
    this.refreshTokens = refreshTokens;
    this.entityManager = entityManager;
  }

  @Override
  public Optional<RefreshToken> findByTokenHash(final String tokenHash) {
    return refreshTokens.findByTokenHash(tokenHash).map(this::toDomain);
  }

  @Override
  public void save(final RefreshToken refreshToken) {
    refreshTokens.save(toEntity(refreshToken));
  }

  @Override
  @Transactional
  public void insert(final RefreshToken refreshToken) {
    entityManager.persist(toEntity(refreshToken));
  }

  private RefreshTokenEntity toEntity(final RefreshToken refreshToken) {
    return new RefreshTokenEntity(
        refreshToken.id(),
        refreshToken.sessionId(),
        refreshToken.accountId().value(),
        refreshToken.tokenHash(),
        refreshToken.rotatedFromId().orElse(null),
        refreshToken.issuedAt(),
        refreshToken.expiresAt(),
        refreshToken.revokedAt().orElse(null));
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
