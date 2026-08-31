package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPendingPlatformSocialLinkJpaRepository
    extends JpaRepository<PendingPlatformSocialLinkEntity, UUID> {

  // PMD.LongVariable: confirmationTokenHash names exactly what it is, same convention
  // PendingPlatformSocialLinkEntity's own class-level suppression already documents.
  @SuppressWarnings("PMD.LongVariable")
  Optional<PendingPlatformSocialLinkEntity> findByConfirmationTokenHash(
      String confirmationTokenHash);
}
