package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPlatformSocialIdentityJpaRepository
    extends JpaRepository<PlatformSocialIdentityEntity, UUID> {

  Optional<PlatformSocialIdentityEntity> findByProviderAndProviderUserId(
      String provider, String providerUserId);

  // ADR-0026: the self-service "Connected accounts" read.
  List<PlatformSocialIdentityEntity> findAllByPlatformAccountId(UUID platformAccountId);
}
