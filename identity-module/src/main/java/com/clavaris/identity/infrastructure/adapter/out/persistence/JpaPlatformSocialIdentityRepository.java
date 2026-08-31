package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PlatformSocialIdentityRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.PlatformSocialIdentity}
 * (framework-free) and {@link PlatformSocialIdentityEntity}.
 */
@Repository
class JpaPlatformSocialIdentityRepository implements PlatformSocialIdentityRepository {

  private final SpringDataPlatformSocialIdentityJpaRepository identities;

  /* package */ JpaPlatformSocialIdentityRepository(
      final SpringDataPlatformSocialIdentityJpaRepository identities) {
    this.identities = identities;
  }

  @Override
  public Optional<PlatformSocialIdentity> findByProviderAndProviderUserId(
      final SocialProvider provider, final String providerUserId) {
    return identities
        .findByProviderAndProviderUserId(provider.name(), providerUserId)
        .map(this::toDomain);
  }

  @Override
  public void save(final PlatformSocialIdentity identity) {
    // Code review finding: saveAndFlush, not save — same "must throw synchronously, right here"
    // reasoning JpaAccountRepository.save()/JpaSocialIdentityRepository.save() already document
    // for the identical problem: a plain save() only stages the insert, deferring the actual
    // unique-constraint check until the surrounding transaction commits, well after
    // ConfirmPendingPlatformSocialLinkService's own try/catch around this call would have already
    // returned.
    identities.saveAndFlush(
        new PlatformSocialIdentityEntity(
            identity.id(),
            identity.platformAccountId().value(),
            identity.provider().name(),
            identity.providerUserId(),
            identity.linkedAt()));
  }

  private PlatformSocialIdentity toDomain(final PlatformSocialIdentityEntity entity) {
    return PlatformSocialIdentity.reconstitute(
        entity.getId(),
        new PlatformAccountId(entity.getPlatformAccountId()),
        SocialProvider.valueOf(entity.getProvider()),
        entity.getProviderUserId(),
        entity.getLinkedAt());
  }
}
