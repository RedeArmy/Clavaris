package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPlatformKnownDeviceJpaRepository
    extends JpaRepository<PlatformKnownDeviceEntity, UUID> {

  Optional<PlatformKnownDeviceEntity> findByPlatformAccountIdAndDeviceTokenHash(
      UUID platformAccountId, String deviceTokenHash);

  boolean existsByPlatformAccountId(UUID platformAccountId);

  // Same bulk-delete rationale as SpringDataKnownDeviceJpaRepository's own identical query.
  @Modifying
  @Query("delete from PlatformKnownDeviceEntity d where d.lastSeenAt < :cutoff")
  long deleteByLastSeenAtBefore(@Param("cutoff") Instant cutoff);
}
