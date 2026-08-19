package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPlatformClientJpaRepository extends JpaRepository<PlatformClientEntity, UUID> {

  boolean existsByClientId(String clientId);

  Optional<PlatformClientEntity> findByClientId(String clientId);
}
