package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.activateplatformsigningkey.PlatformSigningKeyRepository;
import com.clavaris.identity.domain.model.PlatformSigningKey;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.PlatformSigningKey} and {@link
 * PlatformSigningKeyEntity}.
 */
@Repository
class JpaPlatformSigningKeyRepository implements PlatformSigningKeyRepository {

  private final SpringDataPlatformSigningKeyJpaRepository signingKeys;

  /* package */ JpaPlatformSigningKeyRepository(
      final SpringDataPlatformSigningKeyJpaRepository signingKeys) {
    this.signingKeys = signingKeys;
  }

  @Override
  public Optional<PlatformSigningKey> findActive() {
    return signingKeys.findFirstByRetiredAtIsNull().map(this::toDomain);
  }

  @Override
  public void save(final PlatformSigningKey signingKey) {
    signingKeys.save(
        new PlatformSigningKeyEntity(
            signingKey.id(),
            signingKey.kid(),
            signingKey.algorithm(),
            signingKey.activeFrom(),
            signingKey.retiredAt().orElse(null)));
  }

  private PlatformSigningKey toDomain(final PlatformSigningKeyEntity entity) {
    return PlatformSigningKey.reconstitute(
        entity.getId(),
        entity.getKid(),
        entity.getAlgorithm(),
        entity.getActiveFrom(),
        entity.getRetiredAt());
  }
}
