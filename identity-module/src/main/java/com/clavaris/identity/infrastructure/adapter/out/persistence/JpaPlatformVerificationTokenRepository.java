package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Implements the outbound port; mirrors {@link JpaVerificationTokenRepository}. */
@Repository
class JpaPlatformVerificationTokenRepository implements PlatformVerificationTokenRepository {

  private final SpringDataPlatformVerificationTokenJpaRepository tokens;

  /* package */ JpaPlatformVerificationTokenRepository(
      final SpringDataPlatformVerificationTokenJpaRepository tokens) {
    this.tokens = tokens;
  }

  @Override
  public Optional<PlatformVerificationToken> findByTokenHash(final String tokenHash) {
    return tokens.findByTokenHash(tokenHash).map(this::toDomain);
  }

  @Override
  public void save(final PlatformVerificationToken token) {
    tokens.save(
        new PlatformVerificationTokenEntity(
            token.id(),
            token.platformAccountId().value(),
            token.type().name(),
            token.tokenHash(),
            token.expiresAt(),
            token.consumedAt().orElse(null)));
  }

  private PlatformVerificationToken toDomain(final PlatformVerificationTokenEntity entity) {
    return PlatformVerificationToken.reconstitute(
        entity.getId(),
        new PlatformAccountId(entity.getPlatformAccountId()),
        VerificationTokenType.valueOf(entity.getType()),
        entity.getTokenHash(),
        entity.getExpiresAt(),
        entity.getConsumedAt());
  }
}
