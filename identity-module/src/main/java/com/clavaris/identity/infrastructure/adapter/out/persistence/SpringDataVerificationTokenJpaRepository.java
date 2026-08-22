package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataVerificationTokenJpaRepository
    extends JpaRepository<VerificationTokenEntity, UUID> {

  Optional<VerificationTokenEntity> findByTokenHash(String tokenHash);
}
