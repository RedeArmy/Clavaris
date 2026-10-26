package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPendingPlatformSocialLinkJpaRepository
    extends JpaRepository<PendingPlatformSocialLinkEntity, UUID> {

  Optional<PendingPlatformSocialLinkEntity> findByConfirmationTokenHash(
      String confirmationTokenHash);
}
