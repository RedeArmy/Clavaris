package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.SigningKey} and {@link
 * SigningKeyEntity}.
 */
@Repository
class JpaSigningKeyRepository implements SigningKeyRepository {

  private final SpringDataSigningKeyJpaRepository signingKeys;

  /* package */ JpaSigningKeyRepository(final SpringDataSigningKeyJpaRepository signingKeys) {
    this.signingKeys = signingKeys;
  }

  @Override
  public Optional<SigningKey> findActive(final OrganizationId organizationId) {
    return signingKeys
        .findFirstByOrganizationIdAndRetiredAtIsNull(organizationId.value())
        .map(this::toDomain);
  }

  @Override
  public List<SigningKey> findActiveAndRetiredSince(
      final OrganizationId organizationId, final Instant retiredAfter) {
    return signingKeys.findActiveAndRetiredSince(organizationId.value(), retiredAfter).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void save(final SigningKey signingKey) {
    signingKeys.save(
        new SigningKeyEntity(
            signingKey.id(),
            signingKey.organizationId().value(),
            signingKey.kid(),
            signingKey.algorithm(),
            signingKey.activeFrom(),
            signingKey.retiredAt().orElse(null)));
  }

  private SigningKey toDomain(final SigningKeyEntity entity) {
    return SigningKey.reconstitute(
        entity.getId(),
        new OrganizationId(entity.getOrganizationId()),
        entity.getKid(),
        entity.getAlgorithm(),
        entity.getActiveFrom(),
        entity.getRetiredAt());
  }
}
