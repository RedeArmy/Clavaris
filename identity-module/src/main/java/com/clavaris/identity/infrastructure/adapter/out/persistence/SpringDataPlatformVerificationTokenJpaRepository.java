package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPlatformVerificationTokenJpaRepository
    extends JpaRepository<PlatformVerificationTokenEntity, UUID> {

  Optional<PlatformVerificationTokenEntity> findByTokenHash(String tokenHash);
}
