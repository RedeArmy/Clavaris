package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPlatformPasswordCredentialJpaRepository
    extends JpaRepository<PlatformPasswordCredentialEntity, UUID> {

  Optional<PlatformPasswordCredentialEntity> findByPlatformAccountId(UUID platformAccountId);
}
