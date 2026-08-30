package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSocialIdentityJpaRepository extends JpaRepository<SocialIdentityEntity, UUID> {

  Optional<SocialIdentityEntity> findByOrganizationIdAndProviderAndProviderUserId(
      UUID organizationId, String provider, String providerUserId);
}
