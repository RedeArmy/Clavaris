package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSigningKeyJpaRepository extends JpaRepository<SigningKeyEntity, UUID> {

  Optional<SigningKeyEntity> findFirstByOrganizationIdAndRetiredAtIsNull(UUID organizationId);
}
