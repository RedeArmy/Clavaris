package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPendingSocialLinkJpaRepository
    extends JpaRepository<PendingSocialLinkEntity, UUID> {

  Optional<PendingSocialLinkEntity> findByConfirmationTokenHash(String confirmationTokenHash);
}
