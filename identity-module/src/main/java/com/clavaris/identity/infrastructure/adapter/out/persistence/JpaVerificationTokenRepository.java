package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.VerificationToken}
 * (framework-free) and {@link VerificationTokenEntity}.
 */
@Repository
class JpaVerificationTokenRepository implements VerificationTokenRepository {

  private final SpringDataVerificationTokenJpaRepository tokens;

  /* package */ JpaVerificationTokenRepository(
      final SpringDataVerificationTokenJpaRepository tokens) {
    this.tokens = tokens;
  }

  @Override
  public Optional<VerificationToken> findByTokenHash(final String tokenHash) {
    return tokens.findByTokenHash(tokenHash).map(this::toDomain);
  }

  @Override
  public void save(final VerificationToken token) {
    tokens.save(
        new VerificationTokenEntity(
            token.id(),
            token.accountId().value(),
            token.type().name(),
            token.tokenHash(),
            token.expiresAt(),
            token.consumedAt().orElse(null)));
  }

  @Override
  public boolean consumeIfActive(final UUID tokenId, final Instant consumedAt) {
    return tokens.consumeIfActive(tokenId, consumedAt) > 0;
  }

  @Override
  public int invalidateAllActiveForAccount(final AccountId accountId, final Instant now) {
    return tokens.invalidateAllActiveForAccount(accountId.value(), now);
  }

  @Override
  public int invalidateActiveOfTypeForAccount(
      final AccountId accountId, final VerificationTokenType type, final Instant now) {
    return tokens.invalidateActiveOfTypeForAccount(accountId.value(), type.name(), now);
  }

  @Override
  public Optional<VerificationToken> findLatestActiveOfTypeForAccount(
      final AccountId accountId, final VerificationTokenType type, final Instant now) {
    return tokens
        .findFirstByAccountIdAndTypeAndConsumedAtIsNullAndExpiresAtAfterOrderByExpiresAtDesc(
            accountId.value(), type.name(), now)
        .map(this::toDomain);
  }

  private VerificationToken toDomain(final VerificationTokenEntity entity) {
    return VerificationToken.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        VerificationTokenType.valueOf(entity.getType()),
        entity.getTokenHash(),
        entity.getExpiresAt(),
        entity.getConsumedAt());
  }
}
