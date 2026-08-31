package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataKnownDeviceJpaRepository extends JpaRepository<KnownDeviceEntity, UUID> {

  Optional<KnownDeviceEntity> findByAccountIdAndDeviceTokenHash(
      UUID accountId, String deviceTokenHash);

  // TD-PERF-002 (KnownDeviceRetentionJob): a bulk JPQL DELETE, not Spring Data's derived
  // deleteBy...(...) convention — same "one round trip against the database, not one row at a
  // time through the persistence context" rationale
  // SpringDataEventOutboxJpaRepository's own identical bulk delete already documents.
  @Modifying
  @Query("delete from KnownDeviceEntity d where d.lastSeenAt < :cutoff")
  long deleteByLastSeenAtBefore(@Param("cutoff") Instant cutoff);
}
