package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.SocialIdentity} (framework-free)
 * and {@link SocialIdentityEntity}.
 */
@Repository
class JpaSocialIdentityRepository implements SocialIdentityRepository {

  private final SpringDataSocialIdentityJpaRepository identities;

  /* package */ JpaSocialIdentityRepository(
      final SpringDataSocialIdentityJpaRepository identities) {
    this.identities = identities;
  }

  @Override
  public Optional<SocialIdentity> findByOrganizationIdAndProviderAndProviderUserId(
      final OrganizationId organizationId,
      final SocialProvider provider,
      final String providerUserId) {
    return identities
        .findByOrganizationIdAndProviderAndProviderUserId(
            organizationId.value(), provider.name(), providerUserId)
        .map(this::toDomain);
  }

  @Override
  public void save(final SocialIdentity identity) {
    identities.save(
        new SocialIdentityEntity(
            identity.id(),
            identity.accountId().value(),
            identity.organizationId().value(),
            identity.provider().name(),
            identity.providerUserId(),
            identity.linkedAt()));
  }

  private SocialIdentity toDomain(final SocialIdentityEntity entity) {
    return SocialIdentity.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        new OrganizationId(entity.getOrganizationId()),
        SocialProvider.valueOf(entity.getProvider()),
        entity.getProviderUserId(),
        entity.getLinkedAt());
  }
}
