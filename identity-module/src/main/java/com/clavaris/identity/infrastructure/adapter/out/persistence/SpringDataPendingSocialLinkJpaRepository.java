package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPendingSocialLinkJpaRepository
    extends JpaRepository<PendingSocialLinkEntity, UUID> {

  // PMD.LongVariable: confirmationTokenHash names exactly what it is, same convention
  // PendingSocialLinkEntity's own class-level suppression already documents for this exact name.
  @SuppressWarnings("PMD.LongVariable")
  Optional<PendingSocialLinkEntity> findByConfirmationTokenHash(String confirmationTokenHash);
}
