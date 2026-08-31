package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataKnownDeviceJpaRepository extends JpaRepository<KnownDeviceEntity, UUID> {

  Optional<KnownDeviceEntity> findByAccountIdAndUserAgent(UUID accountId, String userAgent);
}
