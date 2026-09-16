package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPlatformVerificationTokenJpaRepository
    extends JpaRepository<PlatformVerificationTokenEntity, UUID> {

  Optional<PlatformVerificationTokenEntity> findByTokenHash(String tokenHash);

  // Mirrors SpringDataVerificationTokenJpaRepository#consumeIfActive exactly — see
  // PlatformVerificationTokenRepository#consumeIfActive's own Javadoc.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update PlatformVerificationTokenEntity t set t.consumedAt = :consumedAt "
          + "where t.id = :tokenId and t.consumedAt is null and t.expiresAt > :consumedAt")
  int consumeIfActive(@Param("tokenId") UUID tokenId, @Param("consumedAt") Instant consumedAt);
}
